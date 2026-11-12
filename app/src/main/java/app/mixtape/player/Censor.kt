package app.mixtape.player

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
import kotlin.math.max
import kotlin.math.min

/** One beeped / muted part of a song, in milliseconds of the song. */
data class CensorRange(val startMs: Long, val endMs: Long, val auto: Boolean = false)

/** Your own clean edit of a song. */
data class CensorEdit(val trackKey: String, val ranges: List<CensorRange>, val mode: CensorMode)

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

    suspend fun save(key: String, ranges: List<CensorRange>, mode: CensorMode) {
        val merged = merge(ranges)
        dao.upsert(
            CensorEntity(
                trackKey = key,
                ranges = encode(merged, mode),
                beep = mode == CensorMode.BEEP || mode == CensorMode.INSTRUMENTAL_BEEP,
                updatedAt = System.currentTimeMillis(),
                deleted = merged.isEmpty(),
            )
        )
    }

    suspend fun delete(key: String) = save(key, emptyList(), CensorMode.BEEP)

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
        /**
         * "start-end,start-end" in ms; "a" marks a spot guessed from lyrics. The two instrumental
         * modes are stored as a prefix ("@inst;" / "@instbeep;") so older versions and the cloud
         * table need no new column; bleep vs silence is the separate beep flag.
         */
        fun encode(r: List<CensorRange>, mode: CensorMode): String {
            val body = r.joinToString(",") { "${it.startMs}-${it.endMs}" + if (it.auto) "a" else "" }
            return when (mode) {
                CensorMode.INSTRUMENTAL -> "@inst;$body"
                CensorMode.INSTRUMENTAL_BEEP -> "@instbeep;$body"
                else -> body
            }
        }

        fun modeOf(ranges: String, beep: Boolean): CensorMode = when {
            ranges.startsWith("@instbeep;") -> CensorMode.INSTRUMENTAL_BEEP
            ranges.startsWith("@inst;") -> CensorMode.INSTRUMENTAL
            beep -> CensorMode.BEEP
            else -> CensorMode.SILENCE
        }

        fun decode(raw: String): List<CensorRange> = raw.substringAfter(";", raw).split(",").mapNotNull { part ->
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

        private fun CensorEntity.toEdit() = CensorEdit(trackKey, if (deleted) emptyList() else decode(ranges), modeOf(ranges, beep))
    }
}

/**
 * Keeps [CleanEditState] pointed at the clean edits of the song playing now and the one after it.
 * The editing itself happens on the decoded sound in [CleanAudioSink].
 */
class CensorEngine(
    private val store: CensorStore,
    private val settings: Settings,
    private val state: CleanEditState,
    private val player: () -> Player?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastKey: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            update()
            handler.postDelayed(this, 150)
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
    }

    /** Also called straight away on song changes so the switch isn't late. */
    fun update() {
        val p = player() ?: return
        val key = p.currentMediaItem?.mediaId
        if (key != lastKey) {
            lastKey = key
            state.useNext = false
        }
        state.current = key?.let { store.get(it) }
        val nextIndex = p.nextMediaItemIndex
        state.next = if (nextIndex != androidx.media3.common.C.INDEX_UNSET && nextIndex < p.mediaItemCount) {
            store.get(p.getMediaItemAt(nextIndex).mediaId)
        } else null
        state.offsetMs = settings.censorOffsetMs.value
    }
}
