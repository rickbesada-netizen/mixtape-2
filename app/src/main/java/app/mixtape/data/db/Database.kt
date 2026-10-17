package app.mixtape.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val trackKey: String,
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Long,
    val artworkUrl: String?,
    val isrc: String?,
    val liked: Boolean = false,
    val likedAt: Long = 0,
) {
    fun toTrack() = Track(
        source = SourceType.fromPrefix(source),
        sourceId = sourceId,
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec,
        artworkUrl = artworkUrl,
        isrc = isrc,
    )

    companion object {
        fun from(t: Track) = TrackEntity(
            trackKey = t.key,
            source = t.source.prefix,
            sourceId = t.sourceId,
            title = t.title,
            artist = t.artist,
            album = t.album,
            durationSec = t.durationSec,
            artworkUrl = t.artworkUrl,
            isrc = t.isrc,
        )
    }
}

object PlaylistOrigin {
    const val LOCAL = "local"
    const val SPOTIFY = "spotify"
    const val CSV = "csv"

    /** Saved from a YouTube / SoundCloud / Spotify link or search result; externalId is the URL. */
    const val LINK = "link"
}

@Entity(tableName = "playlists", indices = [Index("externalId")])
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val origin: String,
    val externalId: String? = null,
    val artworkUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    indices = [Index("trackKey")],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val position: Int,
    val trackKey: String,
)

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val trackCount: Int,
)

object DownloadState {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackKey: String,
    val state: String,
    val progress: Float = 0f,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val filePath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class DownloadWithTrack(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "trackKey", entityColumn = "trackKey")
    val track: TrackEntity?,
)

/** Spotify track key -> YouTube video id. `manual` = user picked it, never overwrite. */
@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey val spotifyKey: String,
    val youtubeId: String,
    val score: Double,
    val manual: Boolean,
)

@Entity(tableName = "history", indices = [Index("trackKey")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackKey: String,
    val playedAt: Long = System.currentTimeMillis(),
)

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tracks: List<TrackEntity>)

    @Query(
        "UPDATE tracks SET title = :title, artist = :artist, album = :album, durationSec = :durationSec, " +
            "artworkUrl = :artworkUrl, isrc = :isrc WHERE trackKey = :trackKey"
    )
    suspend fun updateMeta(
        trackKey: String, title: String, artist: String, album: String?,
        durationSec: Long, artworkUrl: String?, isrc: String?,
    )

    @Query("SELECT * FROM tracks WHERE trackKey = :key")
    suspend fun get(key: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE trackKey IN (:keys)")
    suspend fun getMany(keys: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE liked = 1 ORDER BY likedAt DESC")
    fun liked(): Flow<List<TrackEntity>>

    @Query("SELECT trackKey FROM tracks WHERE liked = 1")
    fun likedKeys(): Flow<List<String>>

    @Query("UPDATE tracks SET liked = :liked, likedAt = :at WHERE trackKey = :key")
    suspend fun setLiked(key: String, liked: Boolean, at: Long)
}

@Dao
abstract class PlaylistDao {
    @Insert
    abstract suspend fun insert(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, artworkUrl = :artworkUrl WHERE id = :id")
    abstract suspend fun updateInfo(id: Long, name: String, artworkUrl: String?)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE origin = :origin AND externalId = :externalId LIMIT 1")
    abstract suspend fun findExternal(origin: String, externalId: String): PlaylistEntity?

    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount " +
            "FROM playlists p ORDER BY p.createdAt DESC"
    )
    abstract fun all(): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON pt.trackKey = t.trackKey " +
            "WHERE pt.playlistId = :playlistId ORDER BY pt.position"
    )
    abstract fun tracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEntries(entries: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    abstract suspend fun clearEntries(playlistId: Long)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    abstract suspend fun entries(playlistId: Long): List<PlaylistTrackEntity>

    @Transaction
    open suspend fun replaceEntries(playlistId: Long, keys: List<String>) {
        clearEntries(playlistId)
        insertEntries(keys.mapIndexed { i, k -> PlaylistTrackEntity(playlistId, i, k) })
    }

    @Transaction
    open suspend fun removeTrack(playlistId: Long, trackKey: String) {
        val remaining = entries(playlistId).filter { it.trackKey != trackKey }.map { it.trackKey }
        replaceEntries(playlistId, remaining)
    }
}

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(d: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE trackKey = :key")
    suspend fun get(key: String): DownloadEntity?

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun allWithTracks(): Flow<List<DownloadWithTrack>>

    @Query("SELECT trackKey FROM downloads WHERE state = 'done'")
    fun doneKeys(): Flow<List<String>>

    @Query("UPDATE downloads SET state = :state, error = :error WHERE trackKey = :key")
    suspend fun setState(key: String, state: String, error: String? = null)

    @Query("UPDATE downloads SET progress = :progress, bytesDone = :done, bytesTotal = :total WHERE trackKey = :key")
    suspend fun setProgress(key: String, progress: Float, done: Long, total: Long)

    @Query("UPDATE downloads SET state = 'done', progress = 1, filePath = :path, bytesDone = :size, bytesTotal = :size, error = NULL WHERE trackKey = :key")
    suspend fun markDone(key: String, path: String, size: Long)

    @Query("DELETE FROM downloads WHERE trackKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface MatchDao {
    @Query("SELECT * FROM matches WHERE spotifyKey = :key")
    suspend fun get(key: String): MatchEntity?

    @Upsert
    suspend fun upsert(m: MatchEntity)

    @Query("DELETE FROM matches WHERE manual = 0")
    suspend fun clearAutomatic()
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(h: HistoryEntity)

    @Query(
        "SELECT t.* FROM tracks t INNER JOIN " +
            "(SELECT trackKey, MAX(playedAt) AS lastPlayed FROM history GROUP BY trackKey) h " +
            "ON h.trackKey = t.trackKey ORDER BY h.lastPlayed DESC LIMIT 100"
    )
    fun recent(): Flow<List<TrackEntity>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(
    entities = [
        TrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class,
        DownloadEntity::class, MatchEntity::class, HistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
    abstract fun matches(): MatchDao
    abstract fun history(): HistoryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "mixtape.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
