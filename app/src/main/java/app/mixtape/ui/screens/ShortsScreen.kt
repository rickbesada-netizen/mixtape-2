package app.mixtape.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.mixtape.AppContainer
import app.mixtape.source.Pager
import app.mixtape.source.VideoItem
import app.mixtape.source.YouTubeVideo
import app.mixtape.source.YouTubeWatch
import app.mixtape.source.YtItem
import app.mixtape.ui.components.LocalContainer
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where Shorts come from: your channels' Shorts, plus popular ones from YouTube search. */
private class ShortsSource(private val container: AppContainer) {
    private val search: Pager<YtItem> = container.youtubeWatch.search("#shorts", YouTubeWatch.SearchKind.VIDEOS)
    private var firstLoad = true

    suspend fun more(): List<VideoItem> = coroutineScope {
        val fromSearch = async {
            runCatching { search.load() }.getOrDefault(emptyList())
                .filterIsInstance<YtItem.Video>().map { it.v }
                .filter { it.isShort || (it.durationSec in 1..70) }
        }
        val fromChannels = if (firstLoad) {
            firstLoad = false
            val subs = runCatching { container.subscriptions.subscriptions().first() }.getOrDefault(emptyList()).shuffled().take(6)
            val gate = Semaphore(3)
            subs.map { s ->
                async {
                    gate.withPermit {
                        runCatching {
                            container.youtubeWatch.channelTab(s.channelId, org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.SHORTS).load()
                        }.getOrDefault(emptyList()).filterIsInstance<YtItem.Video>().map { it.v.copy(channel = it.v.channel.ifBlank { s.name }, isShort = true) }.take(6)
                    }
                }
            }.awaitAll().flatten()
        } else emptyList()
        (fromChannels + fromSearch.await()).shuffled()
    }
}

/** Shorts, full screen: swipe up for the next one. Each one loops until you move on. */
@Composable
fun ShortsScreen(onBack: () -> Unit, openVideo: (String) -> Unit, openChannel: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember { ShortsSource(container) }
    var shorts by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE }
    }
    DisposableEffect(Unit) {
        container.player.pause()
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    LaunchedEffect(Unit) {
        shorts = runCatching { source.more() }.getOrDefault(emptyList()).distinctBy { it.id }
        loading = false
    }

    val pagerState = rememberPagerState { shorts.size }

    LaunchedEffect(pagerState.settledPage, shorts.isNotEmpty()) {
        val page = pagerState.settledPage
        val v = shorts.getOrNull(page) ?: return@LaunchedEffect
        paused = false
        player.stop()
        player.clearMediaItems()
        runCatching { YouTubeVideo.options(v.id) }
            .onSuccess { o ->
                // Shorts are small; 720p is plenty and starts fast.
                val choice = o.choices.filter { it.height <= 720 }.maxByOrNull { it.height } ?: o.choices.minByOrNull { it.height }
                runCatching {
                    player.setMediaSource(buildSource(container, choice, null, o.hlsUrl))
                    player.prepare()
                    player.play()
                }.onFailure { Toast.makeText(context, "Couldn't play this one, swipe on", Toast.LENGTH_SHORT).show() }
                container.videoLibrary.recordWatch(v, o.related)
            }
            .onFailure { Toast.makeText(context, "Couldn't play this one, swipe on", Toast.LENGTH_SHORT).show() }
        if (page >= shorts.size - 3) {
            scope.launch {
                val more = runCatching { source.more() }.getOrDefault(emptyList())
                val seen = shorts.map { it.id }.toHashSet()
                shorts = shorts + more.filter { seen.add(it.id) }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (shorts.isEmpty()) {
            Text("Couldn't load Shorts right now", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { shorts[it].id }) { page ->
                val v = shorts[page]
                Box(
                    Modifier.fillMaxSize().pointerInput(page) {
                        detectTapGestures(onTap = {
                            if (player.isPlaying) { player.pause(); paused = true } else { player.play(); paused = false }
                        })
                    },
                ) {
                    if (page == pagerState.settledPage) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    this.player = player
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                        if (paused) Icon(Icons.Filled.PlayArrow, "Paused", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(72.dp))
                    } else if (v.thumbnail != null) {
                        AsyncImage(model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }

                    // Title and channel at the bottom, actions on the right, like YouTube.
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = v.channelId != null) { v.channelId?.let(openChannel) },
                            ) {
                                if (v.avatar != null) {
                                    AsyncImage(model = v.avatar, contentDescription = null, modifier = Modifier.size(32.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(v.channel, color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                v.title, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ShortAction(Icons.Outlined.WatchLater, "Later") {
                                scope.launch {
                                    val added = container.videoLibrary.toggleWatchLater(v)
                                    Toast.makeText(context, if (added) "Saved to Watch later" else "Removed from Watch later", Toast.LENGTH_SHORT).show()
                                }
                            }
                            ShortAction(Icons.Filled.OndemandVideo, "Open") { player.pause(); openVideo(v.id) }
                        }
                    }
                }
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
        Text("Shorts", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
    }
}

@Composable
private fun ShortAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
