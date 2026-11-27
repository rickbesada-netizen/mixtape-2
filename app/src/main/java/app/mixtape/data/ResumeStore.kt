package app.mixtape.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Where you stopped in a song: position and when you were last playing it. */
data class ResumePoint(val positionMs: Long, val at: Long)

/**
 * "Remember my place" for chosen playlists (audiobooks, mixes, long sets): every song in them picks
 * up where you stopped it last time, and the playlist can continue from the last song you were on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeStore(context: Context, library: LibraryRepository) {
    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _playlists = MutableStateFlow(
        prefs.getStringSet("playlists", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    )
    /** Playlists with "Remember my place" turned on. */
    val playlists: StateFlow<Set<Long>> = _playlists.asStateFlow()

    /** Every song in those playlists. */
    val keys: StateFlow<Set<String>> = _playlists.flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptySet())
        else combine(ids.map { id -> library.playlistTracks(id) }) { lists ->
            lists.flatMap { l -> l.map { it.key } }.toSet()
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _version = MutableStateFlow(0)
    /** Changes whenever a saved spot changes (so the playlist's "Continue" button updates). */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun isOn(playlistId: Long) = playlistId in _playlists.value

    fun setOn(playlistId: Long, on: Boolean) {
        val next = if (on) _playlists.value + playlistId else _playlists.value - playlistId
        prefs.edit().putStringSet("playlists", next.map { it.toString() }.toSet()).apply()
        _playlists.value = next
    }

    fun remembers(key: String) = key in keys.value

    fun get(key: String): ResumePoint? {
        val raw = prefs.getString("p:$key", null) ?: return null
        val pos = raw.substringBefore("|").toLongOrNull() ?: return null
        val at = raw.substringAfter("|", "0").toLongOrNull() ?: 0
        return ResumePoint(pos, at)
    }

    /** Saves the spot; the last few seconds count as finished ("start from the beginning" next time). */
    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (durationMs > 0 && positionMs > durationMs - 8_000) {
            clear(key)
            return
        }
        if (positionMs < 5_000) return
        prefs.edit().putString("p:$key", "$positionMs|${System.currentTimeMillis()}").apply()
        _version.value++
    }

    fun clear(key: String) {
        if (prefs.contains("p:$key")) {
            prefs.edit().remove("p:$key").apply()
            _version.value++
        }
    }

    /** The song in this list you were playing most recently, with where you stopped. */
    fun lastIn(keys: List<String>): Pair<Int, ResumePoint>? = keys.withIndex()
        .mapNotNull { (i, k) -> get(k)?.let { i to it } }
        .maxByOrNull { it.second.at }
}
