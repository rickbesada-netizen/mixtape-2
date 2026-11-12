package app.mixtape.download

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mixtape.MixtapeApp
import app.mixtape.core.model.Track
import app.mixtape.core.net.YouTubeLimits
import app.mixtape.data.LibraryRepository
import app.mixtape.data.Settings
import app.mixtape.data.db.PlaylistDao
import app.mixtape.data.db.PlaylistEntity
import app.mixtape.data.db.PlaylistOrigin
import app.mixtape.source.LinkResolver
import app.mixtape.spotify.SpotifyApi
import app.mixtape.spotify.SpotifyAuth
import app.mixtape.ui.screens.SPOTIFY_LIKED_ID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class PlaylistSyncResult(val playlistName: String, val added: List<Track>, val removed: Int)

/**
 * Keeps saved YouTube / Spotify / SoundCloud playlists in step with the original. When you add a
 * song to the playlist on YouTube or Spotify, the next check picks it up and, if you'd downloaded
 * that playlist, downloads the new song into it too.
 *
 * Runs every 6 hours in the background, shortly after the app opens (at most once an hour), and
 * from the sync button on a playlist.
 */
class PlaylistSync(
    private val context: Context,
    private val library: LibraryRepository,
    private val playlists: PlaylistDao,
    private val links: LinkResolver,
    private val spotify: SpotifyApi,
    private val spotifyAuth: SpotifyAuth,
    private val downloads: DownloadController,
    private val settings: Settings,
) {
    private val lock = Mutex()

    fun canSync(p: PlaylistEntity): Boolean =
        p.externalId != null && (p.origin == PlaylistOrigin.LINK || p.origin == PlaylistOrigin.SPOTIFY)

    /**
     * Refreshes one playlist from where it came from.
     * @param downloadNew download songs that are new since the last check
     * @return null if the playlist can't be refreshed (or the source returned nothing)
     */
    suspend fun syncPlaylist(p: PlaylistEntity, downloadNew: Boolean): PlaylistSyncResult? {
        val ext = p.externalId ?: return null
        val list: List<Track> = when (p.origin) {
            PlaylistOrigin.LINK -> links.refreshCollection(ext).tracks
            PlaylistOrigin.SPOTIFY -> when {
                ext == SPOTIFY_LIKED_ID -> if (spotifyAuth.loggedIn.value) spotify.savedTracks() else return null
                spotifyAuth.loggedIn.value -> spotify.playlistTracks(ext)
                else -> links.refreshCollection("https://open.spotify.com/playlist/$ext").tracks
            }
            else -> return null
        }
        // An empty answer is almost always a failed load, not a playlist someone emptied.
        if (list.isEmpty()) return null

        val before = playlists.entries(p.id).map { it.trackKey }
        val beforeSet = before.toHashSet()
        val added = list.filter { it.key !in beforeSet }.distinctBy { it.key }

        // If the new copy is far shorter than what we have, the load probably got cut off
        // (paging limits, rate limiting), so only add new songs and keep everything already there.
        val suspicious = before.size >= 20 && list.size < before.size / 2
        if (suspicious) {
            if (added.isNotEmpty()) library.addAllToPlaylist(p.id, added)
        } else {
            library.upsertImportedPlaylist(p.origin, ext, p.name, p.artworkUrl, list)
        }
        val removed = if (suspicious) 0 else before.count { k -> list.none { it.key == k } }

        if (downloadNew && added.isNotEmpty()) downloads.enqueue(added)
        return PlaylistSyncResult(p.name, added, removed)
    }

    /** Checks every playlist you've downloaded songs from. Returns what was added. */
    suspend fun syncAll(): List<PlaylistSyncResult> = lock.withLock {
        val downloaded = library.playlists().first()
            .filter { it.downloadedCount > 0 && canSync(it.playlist) }
            .map { it.playlist }
        val out = mutableListOf<PlaylistSyncResult>()
        for (p in downloaded) {
            if (YouTubeLimits.isPaused()) break
            runCatching { syncPlaylist(p, downloadNew = true) }
                .onSuccess { r -> if (r != null && r.added.isNotEmpty()) out += r }
                .onFailure { if (YouTubeLimits.isBotBlock(it)) YouTubeLimits.markBlocked() }
            delay(1500)
        }
        settings.lastPlaylistSyncAt = System.currentTimeMillis()
        out
    }

    /** Sets up the background check (or cancels it when the setting is off). */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        if (!settings.autoSyncPlaylists.value) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints())
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)

        // Also check soon after the app opens, if it's been a while.
        if (System.currentTimeMillis() - settings.lastPlaylistSyncAt > 60L * 60 * 1000) {
            val once = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(constraints())
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, once)
        }
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnlyDownloads.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    companion object {
        const val TAG = "mixtape-playlist-sync"
        private const val PERIODIC = "playlist-sync-periodic"
        private const val ONCE = "playlist-sync-open"
        private const val NOTIFICATION_ID = 1900
    }

    internal fun notifyAdded(results: List<PlaylistSyncResult>) {
        val total = results.sumOf { it.added.size }
        if (total == 0) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val text = if (results.size == 1) {
            "${results[0].playlistName}: ${results[0].added.joinToString(", ") { it.title }.take(120)}"
        } else results.joinToString(", ") { "${it.playlistName} (${it.added.size})" }
        val n = NotificationCompat.Builder(context, DownloadController.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (total == 1) "1 new song added, downloading" else "$total new songs added, downloading")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}

class PlaylistSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MixtapeApp).container
        if (!container.settings.autoSyncPlaylists.value) return Result.success()
        val results = runCatching { container.playlistSync.syncAll() }.getOrElse { return Result.retry() }
        container.playlistSync.notifyAdded(results)
        return Result.success()
    }
}
