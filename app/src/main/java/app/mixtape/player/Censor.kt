package app.mixtape.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import app.mixtape.core.model.Track
import app.mixtape.core.model.display
import app.mixtape.data.Settings
import app.mixtape.data.db.CensorDao
import app.mixtape.data.db.CensorEntity
import app.mixtape.source.CleanConverter
import app.mixtape.source.LyricsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One beeped / muted part of a song, in milliseconds of the song. */
data class CensorRange(val startMs: Long, val endMs: Long, val auto: Boolean = false)

/** Your own clean edit of a song. */
data class CensorEdit(val trackKey: String, val ranges: List<CensorRange>, val beep: Boolean)

/** Keeps everyone's clean edits in memory (they're tiny) so playback can check them instantly. */
class CensorStore(
    private val dao: CensorDao,
    private val lyrics: LyricsClient,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val edits: StateFlow<Map<String, CensorEdit>> = dao.active()
        .map { list -> list.associate { it.trackKey to it.toEdit() }.filterValues { it.ranges.isNotEmpty() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun keys(): Set<String> = edits.value.keys

    fun get(key: String): CensorEdit? = edits.value[key]

    suspend fun save(key: String, ranges: List<CensorRange>, beep: Boolean) {
        val merged = merge(ranges)
        dao.upsert(
            CensorEntity(
                trackKey = key,
                ranges = encode(merged),
                beep = beep,
                updatedAt = System.currentTimeMillis(),
                deleted = merged.isEmpty(),
            )
        )
    }

    suspend fun delete(key: String) = save(key, emptyList(), true)

    /**
     * Guesses where the bad words are from the song's time-synced lyrics. LRCLIB gives a time per
     * line, not per word, so the spot inside the line is estimated from where the word sits in it.
     * These come back marked "auto": listen to each one and nudge it.
     */
    suspend fun autoMark(track: Track): Result<List<CensorRange>> {
        val shown = track.display()
        val found = lyrics.find(shown.title, shown.artist, track.durationSec)
            ?: return Result.failure(IllegalStateException("Couldn't find lyrics for this song"))
        if (found.synced.isEmpty()) {
            return Result.failure(IllegalStateException("This song's lyrics have no timings, so mark it by ear"))
        }
        val filter = CleanConverter.buildFilter(settings.cleanWords.value)
        val out = mutableListOf<CensorRange>()
        val lines = found.synced
        for ((i, line) in lines.withIndex()) {
            if (line.text.isBlank()) continue
            val next = lines.getOrNull(i + 1)?.startMs ?: (line.startMs + 4000)
            val lineLen = min(max(next - line.startMs, 800L), 6000L)
            for (m in filter.findAll(line.text)) {
                val frac = m.range.first.toDouble() / max(line.text.length, 1)
                val wordLen = m.value.length.toDouble() / max(line.text.length, 1)
                val start = line.startMs + (frac * lineLen).toLong() - 250
                val end = line.startMs + ((frac + wordLen) * lineLen).toLong() + 350
                out += CensorRange(max(0, start), end, auto = true)
            }
        }
        return Result.success(out)
    }

    companion object {
        fun encode(r: List<CensorRange>) = r.joinToString(",") { "${it.startMs}-${it.endMs}" + if (it.auto) "a" else "" }

        fun decode(s: String): List<CensorRange> = s.split(",").mapNotNull { part ->
            val auto = part.endsWith("a")
            val p = part.removeSuffix("a").split("-")
            val a = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val b = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (b <= a) null else CensorRange(a, b, auto)
        }

        /** Sorts and joins overlapping parts. */
        fun merge(r: List<CensorRange>): List<CensorRange> {
            val sorted = r.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
            val out = mutableListOf<CensorRange>()
            for (x in sorted) {
                val last = out.lastOrNull()
                if (last != null && x.startMs <= last.endMs) {
                    out[out.size - 1] = CensorRange(last.startMs, max(last.endMs, x.endMs), last.auto && x.auto)
                } else out += x
            }
            return out
        }

        private fun CensorEntity.toEdit() = CensorEdit(trackKey, if (deleted) emptyList() else decode(ranges), beep)
    }
}

/** A steady 1 kHz tone, like a TV bleep. */
class Beeper {
    private var track: AudioTrack? = null
    private var playing = false

    private fun build(): AudioTrack {
        val rate = 44100
        val samples = rate / 2 // half a second, a whole number of 1 kHz cycles, looped
        val pcm = ShortArray(samples) { i -> (sin(2 * PI * 1000.0 * i / rate) * 0.25 * Short.MAX_VALUE).toInt().toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples * 2)
            .build()
        t.write(pcm, 0, samples)
        t.setLoopPoints(0, samples, -1)
        return t
    }

    fun start() {
        if (playing) return
        val t = track ?: runCatching { build() }.getOrNull()?.also { track = it } ?: return
        runCatching { t.play() }
        playing = true
    }

    fun stop() {
        if (!playing) return
        runCatching { track?.pause() }
        playing = false
    }

    fun release() {
        stop()
        runCatching { track?.release() }
        track = null
    }
}

/**
 * Applies clean edits while a song plays: watches the play position and, inside a marked part,
 * mutes the song and plays the bleep (or just mutes). Only runs its fast check while a song with
 * an edit is playing.
 */
class CensorEngine(
    private val store: CensorStore,
    private val settings: Settings,
    private val player: () -> Player?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val beeper = Beeper()
    private var active = false
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            handler.postDelayed(this, check())
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        release()
        beeper.release()
    }

    /** @return how long until the next check */
    private fun check(): Long {
        val p = player() ?: return 500
        val key = p.currentMediaItem?.mediaId
        val edit = key?.let { store.get(it) }
        if (edit == null || !p.isPlaying) {
            release(p)
            return 250
        }
        // A small head start so the bleep covers the start of the word; the offset slider in the
        // editor fixes any remaining lag (Bluetooth headphones are often a bit behind).
        val pos = p.currentPosition + settings.censorOffsetMs.value
        val hit = edit.ranges.any { pos >= it.startMs - LEAD_MS && pos < it.endMs + TAIL_MS }
        if (hit && !active) {
            active = true
            p.volume = 0f
            if (edit.beep) beeper.start()
        } else if (!hit && active) {
            release(p)
        }
        return 10
    }

    private fun release(p: Player? = player()) {
        if (!active) return
        active = false
        p?.volume = 1f
        beeper.stop()
    }

    private companion object {
        const val LEAD_MS = 40L
        const val TAIL_MS = 40L
    }
}
