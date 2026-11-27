package app.mixtape.ui.components

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.mixtape.AppContainer
import app.mixtape.source.YouTubeVideo
import app.mixtape.ui.VideoSession
import app.mixtape.ui.screens.buildSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * YouTube's "playback in feeds": when you stop scrolling, the video at the top of the list plays
 * silently inside its card. Tap it and the video opens at the same moment.
 */
class FeedPreview(context: Context, private val container: AppContainer, private val scope: CoroutineScope) {
    var activeId by mutableStateOf<String?>(null)
        private set
    /** The picture is showing (so the card swaps its thumbnail for the video). */
    var ready by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 500, 1_000).build()
        )
        .build().apply {
            volume = 0f
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { ready = true }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ready = false }
            })
        }

    private var job: Job? = null

    fun play(id: String?) {
        if (id == activeId) return
        stop()
        activeId = id ?: return
        job = scope.launch {
            delay(800) // only once you've stopped on it
            val o = runCatching { YouTubeVideo.options(id) }.getOrNull() ?: return@launch
            if (o.isLive || o.choices.isEmpty()) return@launch
            // Small and quick: the card is only a few centimetres wide.
            val choice = o.choices.firstOrNull { it.muxed }
                ?: o.choices.filter { it.height >= 240 }.minByOrNull { it.height }
                ?: o.choices.first()
            runCatching {
                player.setMediaSource(buildSource(container, choice, null, null))
                player.prepare()
                player.play()
            }
            while (isActive) {
                positionMs = player.currentPosition.coerceAtLeast(0)
                durationMs = player.duration.takeIf { it > 0 } ?: 0
                delay(250)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player.stop()
        player.clearMediaItems()
        activeId = null
        ready = false
        positionMs = 0
    }

    /** Opening this video: it carries on from where the preview got to. */
    fun handOff(id: String) {
        if (id == activeId && ready) Handoff.positions[id] = player.currentPosition
        stop()
    }

    fun release() {
        stop()
        player.release()
    }

    object Handoff {
        val positions = HashMap<String, Long>()
        fun take(id: String): Long? = positions.remove(id)
    }
}

/**
 * Plays the topmost video of this list silently once scrolling stops. [videoIdForKey] turns a
 * list item's key into its video id (null for things that aren't videos).
 */
@Composable
fun rememberFeedPreview(list: LazyListState, videoIdForKey: (Any) -> String?): FeedPreview? {
    val container = LocalContainer.current
    val context = LocalContext.current
    val enabled by container.settings.feedPreviews.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val preview = remember { FeedPreview(context, container, scope) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) preview.stop() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); preview.release() }
    }
    val watching = VideoSession.videoId != null
    LaunchedEffect(list, enabled, watching) {
        if (!enabled || watching) { preview.stop(); return@LaunchedEffect }
        snapshotFlow {
            if (list.isScrollInProgress) null
            else {
                val info = list.layoutInfo
                // The first video whose picture is fully on screen.
                info.visibleItemsInfo.firstOrNull { item ->
                    val id = videoIdForKey(item.key) ?: return@firstOrNull false
                    id.isNotEmpty() && item.offset >= info.viewportStartOffset - 8 &&
                        item.offset + item.size * 0.6f <= info.viewportEndOffset
                }?.let { videoIdForKey(it.key) }
            }
        }
            .distinctUntilChanged()
            .collect { id -> if (id != null) preview.play(id) else if (list.isScrollInProgress) Unit else preview.stop() }
    }
    return if (enabled && !watching) preview else null
}
