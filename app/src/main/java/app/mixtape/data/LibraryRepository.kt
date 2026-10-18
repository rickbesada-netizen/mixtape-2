package app.mixtape.data

import app.mixtape.core.model.Track
import app.mixtape.data.db.AppDatabase
import app.mixtape.data.db.HistoryEntity
import app.mixtape.data.db.PlaylistEntity
import app.mixtape.data.db.PlaylistOrigin
import app.mixtape.data.db.PlaylistTrackEntity
import app.mixtape.data.db.PlaylistWithCount
import app.mixtape.data.db.TrackEntity
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(private val db: AppDatabase) {
    private val tracks = db.tracks()
    private val playlists = db.playlists()
    private val history = db.history()

    /** Inserts new tracks and refreshes metadata of known ones without touching likes. */
    suspend fun saveTracks(list: List<Track>) {
        if (list.isEmpty()) return
        db.withTransaction {
            tracks.insertIgnore(list.map(TrackEntity::from))
            list.forEach {
                tracks.updateMeta(it.key, it.title, it.artist, it.album, it.durationSec, it.artworkUrl, it.isrc)
            }
        }
    }

    suspend fun getTrack(key: String): Track? = tracks.get(key)?.toTrack()

    fun likedTracks(): Flow<List<Track>> = tracks.liked().map { l -> l.map { it.toTrack() } }
    fun likedKeys(): Flow<Set<String>> = tracks.likedKeys().map { it.toSet() }

    suspend fun setLiked(track: Track, liked: Boolean) {
        saveTracks(listOf(track))
        tracks.setLiked(track.key, liked, System.currentTimeMillis())
    }

    fun playlists(): Flow<List<PlaylistWithCount>> = playlists.all()
    fun keysInPlaylists(): Flow<Set<String>> = playlists.keysInPlaylists().map { it.toSet() }
    fun playlist(id: Long): Flow<PlaylistEntity?> = playlists.observe(id)
    fun playlistTracks(id: Long): Flow<List<Track>> =
        playlists.tracks(id).map { l -> l.map { it.toTrack() } }

    suspend fun createPlaylist(name: String): Long =
        playlists.insert(PlaylistEntity(name = name, origin = PlaylistOrigin.LOCAL))

    /** Called before a delete so the cloud copy can be removed too. */
    var onPlaylistDeleted: (suspend (syncId: String) -> Unit)? = null

    suspend fun deletePlaylist(id: Long) {
        playlists.get(id)?.syncId?.let { onPlaylistDeleted?.invoke(it) }
        db.withTransaction {
            playlists.clearEntries(id)
            playlists.delete(id)
        }
    }

    suspend fun addToPlaylist(playlistId: Long, track: Track) {
        saveTracks(listOf(track))
        val next = playlists.maxPosition(playlistId) + 1
        playlists.insertEntries(listOf(PlaylistTrackEntity(playlistId, next, track.key)))
        playlists.touch(playlistId, System.currentTimeMillis())
    }

    suspend fun removeFromPlaylist(playlistId: Long, trackKey: String) {
        playlists.removeTrack(playlistId, trackKey)
        playlists.touch(playlistId, System.currentTimeMillis())
    }

    /** Songs you have (downloaded, liked or in a playlist) matching [query], with their playlists. */
    suspend fun searchLibrary(query: String, downloadsOnly: Boolean): List<Pair<Track, List<String>>> {
        val q = "%" + query.trim().replace("%", "").replace("_", "") + "%"
        val found = if (downloadsOnly) tracks.searchDownloads(q) else tracks.searchLibrary(q)
        if (found.isEmpty()) return emptyList()
        val names = found.map { it.trackKey }.chunked(500)
            .flatMap { playlists.playlistNamesFor(it) }
            .groupBy({ it.trackKey }, { it.name })
        return found.map { it.toTrack() to (names[it.trackKey]?.distinct() ?: emptyList()) }
    }

    /** Creates or refreshes an imported playlist (Spotify, CSV), keyed by its external id. */
    suspend fun upsertImportedPlaylist(
        origin: String,
        externalId: String,
        name: String,
        artworkUrl: String?,
        list: List<Track>,
    ): Long {
        saveTracks(list)
        val existing = playlists.findExternal(origin, externalId)
        val id = if (existing != null) {
            playlists.updateInfo(existing.id, name, artworkUrl)
            playlists.touch(existing.id, System.currentTimeMillis())
            existing.id
        } else {
            playlists.insert(
                PlaylistEntity(name = name, origin = origin, externalId = externalId, artworkUrl = artworkUrl)
            )
        }
        playlists.replaceEntries(id, list.map { it.key }.distinct())
        return id
    }

    suspend fun recordPlay(key: String) {
        if (tracks.get(key) != null) history.insert(HistoryEntity(trackKey = key))
    }

    fun recent(): Flow<List<Track>> = history.recent().map { l -> l.map { it.toTrack() } }
    suspend fun clearHistory() = history.clear()
}
