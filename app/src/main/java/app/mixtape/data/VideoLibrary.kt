package app.mixtape.data

import android.content.Context
import app.mixtape.core.model.SourceType
import app.mixtape.source.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A video you watched: where you got to, and what YouTube suggested after it (for "For you"). */
data class WatchEntry(
    val video: VideoItem,
    val watchedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
    val related: List<VideoItem>,
) {
    /** 0..1 of the way through, for the red bar under thumbnails. */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Your YouTube side: watch history with resume positions, and Watch later. History is kept on the
 * phone (and in backups); Watch later is a normal Mixtape playlist, so it syncs to your account.
 */
class VideoLibrary(context: Context, private val library: LibraryRepository) {
    private val file = File(context.filesDir, "video_history.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _history = MutableStateFlow<List<WatchEntry>>(emptyList())
    val history: StateFlow<List<WatchEntry>> = _history.asStateFlow()

    init {
        scope.launch { runCatching { _history.value = read() } }
    }

    // ---------------------------------------------------------------- history + resume

    fun recordWatch(video: VideoItem, related: List<VideoItem>) {
        val prev = _history.value.firstOrNull { it.video.id == video.id }
        val entry = WatchEntry(
            video = video,
            watchedAt = System.currentTimeMillis(),
            positionMs = prev?.positionMs ?: 0,
            durationMs = prev?.durationMs ?: video.durationSec * 1000,
            related = related.take(12).ifEmpty { prev?.related.orEmpty() },
        )
        update { list -> listOf(entry) + list.filter { it.video.id != video.id } }
    }

    fun saveProgress(videoId: String, positionMs: Long, durationMs: Long) {
        val list = _history.value
        val i = list.indexOfFirst { it.video.id == videoId }
        if (i < 0) return
        val e = list[i]
        if (kotlin.math.abs(e.positionMs - positionMs) < 3000 && e.durationMs == durationMs) return
        update { l -> l.map { if (it.video.id == videoId) it.copy(positionMs = positionMs, durationMs = durationMs) else it } }
    }

    /** Where to pick up a video again (null if not started or basically finished). */
    fun resumeAt(videoId: String): Long? {
        val e = _history.value.firstOrNull { it.video.id == videoId } ?: return null
        return e.positionMs.takeIf { it > 10_000 && (e.durationMs <= 0 || it < e.durationMs - 15_000) }
    }

    fun progressOf(videoId: String): Float = _history.value.firstOrNull { it.video.id == videoId }?.progress ?: 0f

    fun remove(videoId: String) = update { list -> list.filter { it.video.id != videoId } }

    fun clear() = update { emptyList() }

    /** Recommendations: what YouTube suggested after your recent videos, mixed together. */
    fun forYou(exclude: Set<String> = emptySet()): List<VideoItem> {
        val recent = _history.value.take(15)
        val watched = _history.value.map { it.video.id }.toHashSet()
        val lists = recent.map { it.related }
        val out = mutableListOf<VideoItem>()
        val seen = HashSet<String>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) {
            val v = l.getOrNull(i) ?: continue
            if (v.id in watched || v.id in exclude || v.isShort || !seen.add(v.id)) continue
            out += v
        }
        return out
    }

    private fun update(f: (List<WatchEntry>) -> List<WatchEntry>) {
        val next = f(_history.value).take(MAX)
        _history.value = next
        scope.launch { runCatching { write(next) } }
    }

    private fun read(): List<WatchEntry> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val v = o.optJSONObject("v")?.let { VideoItem.fromJson(it) } ?: return@mapNotNull null
            val rel = o.optJSONArray("r")
            WatchEntry(
                v, o.optLong("at"), o.optLong("pos"), o.optLong("dur"),
                if (rel == null) emptyList() else (0 until rel.length()).mapNotNull { j -> rel.optJSONObject(j)?.let { VideoItem.fromJson(it) } },
            )
        }
    }

    @Synchronized
    private fun write(list: List<WatchEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("v", e.video.toJson()).put("at", e.watchedAt).put("pos", e.positionMs).put("dur", e.durationMs)
                    .put("r", JSONArray().apply { e.related.forEach { put(it.toJson()) } })
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    /** For backups. */
    fun exportJson(): String = file.takeIf { it.exists() }?.readText() ?: "[]"

    fun importJson(json: String) {
        runCatching {
            file.writeText(json)
            _history.value = read()
        }
    }

    // ---------------------------------------------------------------- Watch later

    /** Watch later, newest first. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun watchLater(): Flow<List<VideoItem>> = library.playlists()
        .map { list -> list.firstOrNull { it.playlist.name == WATCH_LATER }?.playlist?.id }
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else library.playlistTracks(id).map { tracks -> tracks.map { VideoItem.fromTrack(it) }.reversed() }
        }

    suspend fun isInWatchLater(videoId: String): Boolean {
        val id = laterId(create = false) ?: return false
        return library.playlistTracksOnce(id).any { it.sourceId == videoId }
    }

    suspend fun toggleWatchLater(v: VideoItem): Boolean {
        val id = laterId(create = true)!!
        val track = v.toTrack().copy(source = SourceType.YOUTUBE_VIDEO)
        val has = library.playlistTracksOnce(id).any { it.sourceId == v.id }
        if (has) library.removeFromPlaylist(id, track.key) else library.addToPlaylist(id, track)
        return !has
    }

    private suspend fun laterId(create: Boolean): Long? {
        library.playlists().first().firstOrNull { it.playlist.name == WATCH_LATER }?.let { return it.playlist.id }
        return if (create) library.createPlaylist(WATCH_LATER) else null
    }

    companion object {
        const val WATCH_LATER = "Watch later"
        private const val MAX = 500
    }
}
