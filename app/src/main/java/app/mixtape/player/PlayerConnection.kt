package app.mixtape.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.mixtape.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val key: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
)

data class PlayerUiState(
    val nowPlaying: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<NowPlaying> = emptyList(),
    val currentIndex: Int = -1,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val speed: Float = 1f,
    val keepPitch: Boolean = true,
    /** Similar songs are being fetched to continue the queue. */
    val radioLoading: Boolean = false,
)

/** UI-side handle on the PlaybackService via a MediaController. */
class PlayerConnection(
    private val context: Context,
    private val settings: app.mixtape.data.Settings,
    private val library: app.mixtape.data.LibraryRepository,
    private val radio: app.mixtape.source.RadioEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var connecting = false
    private var pending: ((MediaController) -> Unit)? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
            pending?.invoke(c)
            pending = null
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending = block
            connect()
        }
    }

    private var consecutiveErrors = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) consecutiveErrors = 0
            refresh()
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                controller?.let { maybeAutoplay(it, force = false) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val botBlock = app.mixtape.core.net.YouTubeLimits.isBotBlock(error)
            val msg = if (botBlock) app.mixtape.core.net.YouTubeLimits.FRIENDLY_MESSAGE
            else error.cause?.message ?: error.message ?: "Playback error"
            _errors.tryEmit(msg)
            consecutiveErrors++
            if (botBlock) return
            // Skip a track that cannot be played rather than stalling the queue (but don't
            // race through everything when the network is simply down).
            controller?.let { c ->
                if (consecutiveErrors <= 3 && c.hasNextMediaItem()) {
                    c.seekToNextMediaItem()
                    c.prepare()
                    c.play()
                }
            }
        }
    }

    private fun MediaItem.toNowPlaying() = NowPlaying(
        key = mediaId,
        title = mediaMetadata.title?.toString() ?: "",
        artist = mediaMetadata.artist?.toString() ?: "",
        artworkUrl = mediaMetadata.artworkUri?.toString(),
    )

    private fun refresh() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toNowPlaying() }
        _state.value = PlayerUiState(
            nowPlaying = c.currentMediaItem?.toNowPlaying(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem() || c.currentPosition > 3000,
            speed = c.playbackParameters.speed,
            keepPitch = settings.keepPitch.value,
            radioLoading = radioJob?.isActive == true,
        )
        if (c.isPlaying) startTicker() else stopTicker()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val c = controller ?: break
                _state.value = _state.value.copy(
                    positionMs = c.currentPosition.coerceAtLeast(0),
                    durationMs = c.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                )
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    // ---- Autoplay (song radio) ----

    private var radioJob: Job? = null
    private var radioSeedKey: String? = null
    /** Songs autoplay added, so changing the autoplay source can swap them out. */
    private val radioKeys = HashSet<String>()

    /**
     * When the queue is about to run out, append songs similar to the one playing, the way Spotify
     * keeps going after a song you searched for. [force] starts it regardless of the setting
     * ("Start radio").
     */
    private fun maybeAutoplay(c: MediaController, force: Boolean) {
        if (!force && !settings.autoplay.value) return
        if (c.mediaItemCount == 0) return
        if (!force && c.repeatMode != Player.REPEAT_MODE_OFF) return
        val remaining = if (c.shuffleModeEnabled) {
            if (c.hasNextMediaItem()) 2 else 0
        } else c.mediaItemCount - 1 - c.currentMediaItemIndex
        if (!force && remaining > 1) return
        val seedKey = c.currentMediaItem?.mediaId ?: return
        if (radioJob?.isActive == true) return
        if (!force && seedKey == radioSeedKey) return
        radioSeedKey = seedKey

        val queued = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            val key = item.mediaId
            Track(
                source = runCatching { app.mixtape.core.model.SourceType.fromKey(key) }
                    .getOrDefault(app.mixtape.core.model.SourceType.YOUTUBE),
                sourceId = Track.sourceIdOf(key),
                title = item.mediaMetadata.title?.toString() ?: "",
                artist = item.mediaMetadata.artist?.toString() ?: "",
            )
        }
        radioJob = scope.launch {
            refresh()
            try {
                val seed = library.getTrack(seedKey) ?: queued.firstOrNull { it.key == seedKey } ?: return@launch
                val recent = runCatching { library.recent().first().take(40) }.getOrDefault(emptyList())
                val more = radio.similarTo(seed, queued + recent)
                if (more.isEmpty()) return@launch
                library.saveTracks(more)
                radioKeys += more.map { it.key }
                val cc = controller ?: return@launch
                val before = cc.mediaItemCount
                val ended = cc.playbackState == Player.STATE_ENDED
                cc.addMediaItems(more.map(PlaybackService::controllerItem))
                if (ended && before > 0) {
                    cc.seekTo(before, 0)
                    cc.prepare()
                    cc.play()
                }
            } finally {
                refresh()
            }
        }
    }

    /** Plays this song, then a radio of similar songs after it. */
    fun startRadio(track: Track) {
        radioJob?.cancel()
        radioSeedKey = null
        withController { c ->
            c.shuffleModeEnabled = false
            c.setMediaItems(listOf(PlaybackService.controllerItem(track)), 0, 0)
            c.prepare()
            c.play()
            maybeAutoplay(c, force = true)
        }
    }

    /** Switch where autoplay's songs come from, and redo the upcoming autoplay songs straight away. */
    fun setRadioSource(source: app.mixtape.data.RadioSource) {
        settings.setRadioSource(source)
        withController { c ->
            radioJob?.cancel()
            val current = c.currentMediaItemIndex
            for (i in c.mediaItemCount - 1 downTo current + 1) {
                if (c.getMediaItemAt(i).mediaId in radioKeys) c.removeMediaItem(i)
            }
            radioSeedKey = null
            if (settings.autoplay.value && c.mediaItemCount > 0) maybeAutoplay(c, force = true)
        }
    }

    /** The exact play position right now (the UI state only updates twice a second). */
    fun positionNow(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: _state.value.positionMs

    fun seekToAndPlay(ms: Long) = withController { it.seekTo(ms.coerceAtLeast(0)); it.play() }

    fun setAutoplay(on: Boolean) {
        settings.setAutoplay(on)
        if (on) controller?.let { maybeAutoplay(it, force = false) }
        refresh()
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        radioJob?.cancel()
        radioSeedKey = null
        radioKeys.clear()
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(tracks.map(PlaybackService::controllerItem), startIndex.coerceIn(tracks.indices), 0)
            c.prepare()
            c.play()
        }
    }

    fun playNext(track: Track) = withController { c ->
        val item = PlaybackService.controllerItem(track)
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item); c.prepare(); c.play()
        } else c.addMediaItem(c.currentMediaItemIndex + 1, item)
    }

    fun addToQueue(track: Track) = withController { c ->
        val item = PlaybackService.controllerItem(track)
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item); c.prepare(); c.play()
        } else c.addMediaItem(item)
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
            c.play()
        }
    }

    fun pause() {
        controller?.pause()
    }

    /** 0.25x to 4x. Saved, so it carries on to the next song and the next time you open the app. */
    fun setSpeed(speed: Float) {
        settings.setSpeed(speed)
        applySpeed()
    }

    fun setKeepPitch(keep: Boolean) {
        settings.setKeepPitch(keep)
        applySpeed()
    }

    private fun applySpeed() = withController {
        it.playbackParameters = speedParams(settings.speed.value, settings.keepPitch.value)
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekTo(index, 0); it.play() }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** After a manual re-match, restart the current item so the new audio is used. */
    fun reloadCurrent() = withController { c ->
        val idx = c.currentMediaItemIndex
        val current = c.currentMediaItem ?: return@withController
        val item = MediaItem.Builder().setMediaId(current.mediaId).setMediaMetadata(current.mediaMetadata).build()
        c.removeMediaItem(idx)
        c.addMediaItem(idx, item)
        c.seekTo(idx, 0)
        c.prepare()
        c.play()
    }
}
