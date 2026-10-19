package app.mixtape.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.mixtape.MixtapeApp
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.model.display
import app.mixtape.ui.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Hosts the ExoPlayer inside a MediaSessionService, which gives us the media notification,
 * lock-screen and Bluetooth controls, and keeps audio alive in the background.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val container = (application as MixtapeApp).container

        val dataSourceFactory = DataSource.Factory {
            MixtapeDataSource(
                resolver = container.resolver,
                http = container.http,
                key = container.crypto.dataKey,
                durationOf = { k -> runBlocking { container.library.getTrack(k)?.durationSec ?: 0L } },
            )
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Start at the speed the listener last chose.
        player.playbackParameters = speedParams(container.settings.speed.value, container.settings.keepPitch.value)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val key = mediaItem?.mediaId ?: return
                scope.launch(Dispatchers.IO) { container.library.recordPlay(key) }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()
    }

    /**
     * Controllers can't send playable URIs across the session boundary, so items arrive with just
     * an id (the track key) and metadata. We attach our routing URI here.
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future(Dispatchers.IO) {
            val downloads = (application as MixtapeApp).container.downloads
            mediaItems.map { item ->
                val key = item.mediaId
                val offline = downloads.completedFile(key) != null
                buildPlayableItem(key, item.mediaMetadata, offline)
            }.toMutableList()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun buildPlayableItem(key: String, metadata: MediaMetadata, offline: Boolean): MediaItem {
            val isSoundCloud = SourceType.fromKey(key) == SourceType.SOUNDCLOUD
            val useHls = isSoundCloud && !offline
            return MediaItem.Builder()
                .setMediaId(key)
                .setUri(TrackUri.of(key, hls = useHls))
                .setMimeType(if (useHls) MimeTypes.APPLICATION_M3U8 else null)
                .setMediaMetadata(metadata)
                .build()
        }

        /** What the UI sends to the session. */
        fun controllerItem(track: Track): MediaItem {
            val extras = Bundle().apply { putString("source", track.source.label) }
            val shown = track.display()
            val meta = MediaMetadata.Builder()
                .setTitle(shown.title)
                .setArtist(shown.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.artworkUrl?.let { android.net.Uri.parse(it) })
                .setDurationMs(if (track.durationSec > 0) track.durationSec * 1000 else null)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)
                .build()
            return MediaItem.Builder().setMediaId(track.key).setMediaMetadata(meta).build()
        }
    }
}
