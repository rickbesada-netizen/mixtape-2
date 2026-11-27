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
import kotlinx.coroutines.launch
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
    context: android.content.Context? = null,
    /** Finds a song's details (title, artist, length) for clean mode. */
    private val trackLookup: suspend (String) -> Track? = { null },
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
        val shown = track.copy(title = track.originalTitle ?: track.title, artist = track.originalArtist ?: track.artist).display()
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
            val gap = max(next - line.startMs, 600L)
            for (m in filter.findAll(line.text)) {
                val w = line.words
                if (w.isNotEmpty()) {
                    // Word-by-word timings: exact.
                    val at = w.lastOrNull { it.charIndex <= m.range.first } ?: w.first()
                    val after = w.firstOrNull { it.charIndex > m.range.last }?.startMs
                        ?: min(at.startMs + 700, line.startMs + gap)
                    out += CensorRange(max(0, at.startMs - 120), max(after, at.startMs + 250) + 120, auto = true)
                } else {
                    // Only a time per line: a line is sung at roughly 13 letters a second and
                    // finishes before the next one starts, so place the word inside that.
                    val sung = min(gap * 9 / 10, max(line.text.length * 75L, 900L))
                    val len = max(line.text.length, 1).toDouble()
                    val start = line.startMs + (m.range.first / len * sung).toLong() - 300
                    val end = line.startMs + ((m.range.last + 1) / len * sung).toLong() + 400
                    out += CensorRange(max(0, start), end, auto = true)
                }
            }
        }
        return Result.success(merge(out))
    }

    // ---- Clean mode: cleaned automatically while playing ----

    private val autoPrefs = context?.getSharedPreferences("auto_clean", android.content.Context.MODE_PRIVATE)
    /** Songs checked this run: the bad-word spots found (empty = nothing to clean). */
    private val autoCache = java.util.concurrent.ConcurrentHashMap<String, List<CensorRange>>()
    private val autoLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Songs whose lyrics couldn't be found this run (tried again next time the app starts). */
    private val autoMissing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Clean mode's edit for a song: your own edit always wins; otherwise the spots found from its
     * lyrics, found once (in the background) and remembered, so downloaded songs stay clean offline.
     */
    fun autoEdit(key: String): CensorEdit? {
        autoCache[key]?.let { return if (it.isEmpty()) null else CensorEdit(key, it, settings.autoCleanMode.value) }
        autoPrefs?.getString(key, null)?.let { raw ->
            val r = if (raw.isEmpty()) emptyList() else decode(raw)
            autoCache[key] = r
            return if (r.isEmpty()) null else CensorEdit(key, r, settings.autoCleanMode.value)
        }
        if (key !in autoMissing && autoLoading.add(key)) {
            scope.launch {
                try {
                    val track = trackLookup(key) ?: return@launch
                    autoMark(track)
                        .onSuccess { r ->
                            autoCache[key] = r
                            autoPrefs?.edit()?.putString(key, encode(r, CensorMode.BEEP))?.apply()
                        }
                        .onFailure { autoMissing += key }
                } catch (e: Exception) {
                    autoMissing += key
                } finally {
                    autoLoading.remove(key)
                }
            }
        }
        return null
    }

    /** Whether clean mode is still looking up this song's lyrics. */
    fun autoChecking(key: String) = key in autoLoading

    /** Clean mode found no timed lyrics for this song, so it plays as it is. */
    fun autoUnavailable(key: String) = key in autoMissing

    /** How many bad words clean mode is covering in this song (null = not checked yet). */
    fun autoCount(key: String): Int? = autoCache[key]?.size

    /** Forget what clean mode found (after you change your word list). */
    fun clearAuto() {
        autoCache.clear()
        autoMissing.clear()
        autoPrefs?.edit()?.clear()?.apply()
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
        state.current = key?.let { editFor(it) }
        val nextIndex = p.nextMediaItemIndex
        state.next = if (nextIndex != androidx.media3.common.C.INDEX_UNSET && nextIndex < p.mediaItemCount) {
            editFor(p.getMediaItemAt(nextIndex).mediaId)
        } else null
        state.offsetMs = settings.censorOffsetMs.value
    }

    /** Your own clean edit, or with clean mode on, the one made automatically from the lyrics. */
    private fun editFor(key: String): CensorEdit? =
        store.get(key) ?: if (settings.autoClean.value) store.autoEdit(key) else null
}
