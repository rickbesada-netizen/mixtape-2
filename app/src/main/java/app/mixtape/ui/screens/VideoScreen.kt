package app.mixtape.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.mixtape.AppContainer
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.model.formatDuration
import app.mixtape.download.VideoExport
import app.mixtape.player.EncryptedFileDataSource
import app.mixtape.player.MixtapeDataSource
import app.mixtape.player.Pip
import app.mixtape.player.formatSpeed
import app.mixtape.player.speedParams
import app.mixtape.source.CaptionCue
import app.mixtape.source.CaptionTrack
import app.mixtape.source.Comment
import app.mixtape.source.Pager
import app.mixtape.source.SkipSegment
import app.mixtape.source.VideoChapter
import app.mixtape.source.VideoChoice
import app.mixtape.source.VideoItem
import app.mixtape.source.VideoOptions
import app.mixtape.source.VideoQueue
import app.mixtape.source.YouTubeVideo
import app.mixtape.source.YouTubeWatch
import app.mixtape.ui.components.CastButton
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.SpeedSheet
import app.mixtape.ui.components.VideoCard
import app.mixtape.ui.components.rememberCastConnected
import app.mixtape.ui.components.viewsText
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

internal fun buildSource(container: AppContainer, choice: VideoChoice?, offline: File?, hls: String?): MediaSource {
    if (offline != null) {
        val factory = DataSource.Factory { EncryptedFileDataSource(container.crypto.dataKey) }
        return ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.fromFile(offline)))
    }
    if (choice == null && hls != null) {
        // Live streams come as HLS.
        return HlsMediaSource.Factory(DefaultHttpDataSource.Factory()).createMediaSource(MediaItem.fromUri(hls))
    }
    val c = choice ?: throw IllegalStateException("No video stream")
    val factory = DataSource.Factory {
        MixtapeDataSource(container.resolver, container.http, container.crypto.dataKey) { 0L }
    }
    val pf = ProgressiveMediaSource.Factory(factory)
    val video = pf.createMediaSource(MediaItem.fromUri(c.videoUrl))
    val audio = c.audioUrl
    return if (c.muxed || audio == null) video
    else MergingMediaSource(video, pf.createMediaSource(MediaItem.fromUri(audio)))
}

/** The panels that slide up under the video, like YouTube's. */
private enum class Panel { CHAPTERS, COMMENTS, DESCRIPTION }

private fun clock(ms: Long): String = formatDuration((ms / 1000).coerceAtLeast(0)).ifEmpty { "0:00" }

/** A YouTube look-alike pill button. */
@Composable
private fun Pill(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun PillIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Pill(onClick) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The watch page, laid out like YouTube's: controls on the video, title and channel below, a row
 * of action pills, a comments card, then what to watch next. Chapters, comments and the
 * description open in a panel under the video. No ads; sponsor segments are skipped.
 */
@Composable
fun VideoScreen(
    videoId: String,
    onBack: () -> Unit,
    openChannel: (String) -> Unit = {},
    openVideo: (String) -> Unit = {},
    minimized: Boolean = false,
    miniBottomPadding: androidx.compose.ui.unit.Dp = 64.dp,
    onMinimize: () -> Unit = onBack,
    onExpand: () -> Unit = {},
    onClose: () -> Unit = onBack,
) {
    // Back (when nothing else is open) shrinks the video into the corner, like YouTube.
    // Declared first so fullscreen / panel Back handlers below win while they're open.
    androidx.activity.compose.BackHandler(enabled = !minimized) { onMinimize() }
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoKey = Track.keyOf(SourceType.YOUTUBE_VIDEO, videoId)
    val maxHeight by container.settings.videoQuality.collectAsStateWithLifecycle()
    val autoplay by container.settings.videoAutoplay.collectAsStateWithLifecycle()
    val backgroundPlay by container.settings.videoBackgroundPlay.collectAsStateWithLifecycle()
    val sponsorCats by container.settings.sponsorCategories.collectAsStateWithLifecycle()
    val captionPref by container.settings.captionsLanguage.collectAsStateWithLifecycle()
    val doneFlow = remember { container.downloads.doneKeys() }
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())
    val history by container.videoLibrary.history.collectAsStateWithLifecycle()
    val subsFlow = remember { container.subscriptions.subscribedIds() }
    val subscribed by subsFlow.collectAsStateWithLifecycle(emptySet())

    var options by remember { mutableStateOf<VideoOptions?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<VideoChoice?>(null) }
    var offlineFile by remember { mutableStateOf<File?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var zoomFill by remember { mutableStateOf(false) }
    var fullscreenFromRotation by remember { mutableStateOf(false) }
    var votes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var segments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var captionTrack by remember { mutableStateOf<CaptionTrack?>(null) }
    var cues by remember { mutableStateOf<List<CaptionCue>>(emptyList()) }
    var captionText by remember { mutableStateOf("") }
    var inLater by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(false) }
    var commentsPager by remember { mutableStateOf<Pager<Comment>?>(null) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var handedOff by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }

    val player = remember {
        // Start playing after a second of video instead of 2.5, like YouTube.
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()
        ExoPlayer.Builder(context).setLoadControl(load).build()
    }
    val peek = remember(videoId) { app.mixtape.ui.VideoPeek.get(videoId) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var videoSpeed by remember { mutableFloatStateOf(1f) }
    var videoKeepPitch by remember { mutableStateOf(true) }
    LaunchedEffect(videoSpeed, videoKeepPitch) { player.playbackParameters = speedParams(videoSpeed, videoKeepPitch) }
    LaunchedEffect(loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    val activity = context as? Activity
    val castConnected = rememberCastConnected()
    val inPip by Pip.inPip.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val track = options?.let {
        Track(SourceType.YOUTUBE, it.videoId, it.title, it.uploader, durationSec = it.durationSec, artworkUrl = it.thumbnailUrl)
    }
    val videoItem = options?.let {
        VideoItem(it.videoId, it.title, it.uploader, it.channelUrl, it.thumbnailUrl, it.durationSec, it.viewCount, it.uploaded, it.channelAvatar, it.isLive)
    }
    val upNext: List<VideoItem> = remember(options) {
        val queued = VideoQueue.after(videoId)
        (queued + options?.related.orEmpty().filter { !it.isShort }).distinctBy { it.id }.filter { it.id != videoId }
    }
    val currentUpNext by rememberUpdatedState(upNext)
    val currentAutoplay by rememberUpdatedState(autoplay)
    val currentTrack by rememberUpdatedState(track)
    val chapters = options?.chapters.orEmpty()
    val currentChapter = chapters.lastOrNull { it.startSec * 1000L <= position }

    fun poke() { touchedAt = System.currentTimeMillis(); controlsVisible = true }

    fun saveProgress() {
        val d = player.duration
        if (d > 0) container.videoLibrary.saveProgress(videoId, player.currentPosition, d)
    }

    DisposableEffect(Unit) {
        container.player.pause()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                Pip.videoPlaying = playing
                activity?.let { Pip.update(it) }
                if (!playing) saveProgress()
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (player.duration > 0) duration = player.duration
                if (state == Player.STATE_ENDED) {
                    controlsVisible = true
                    container.videoLibrary.saveProgress(videoId, player.duration, player.duration)
                    val next = currentUpNext.firstOrNull()
                    if (currentAutoplay && next != null) openVideo(next.id)
                }
            }
        }
        player.addListener(listener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    saveProgress()
                    val wasPlaying = player.isPlaying
                    val t = currentTrack
                    // Background play: hand the sound to the music player so it keeps going.
                    if (wasPlaying && t != null && !Pip.inPip.value && container.settings.videoBackgroundPlay.value) {
                        container.player.playTracks(listOf(t), 0, startPositionMs = player.currentPosition)
                        handedOff = true
                    }
                    player.pause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (handedOff) {
                        handedOff = false
                        val pos = container.player.positionNow()
                        container.player.pause()
                        if (pos > 0) player.seekTo(pos)
                        player.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            saveProgress()
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            Pip.videoPlaying = false
            activity?.let { Pip.update(it) }
            player.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(videoId) {
        val resume = container.videoLibrary.resumeAt(videoId)
        offlineFile = container.downloads.completedFile(videoKey)
        if (offlineFile != null) {
            player.setMediaSource(buildSource(container, null, offlineFile, null), resume ?: 0L)
            player.prepare()
            player.play()
        }
        // These don't need the video's details, so load them alongside.
        launch { votes = container.youtubeWatch.votes(videoId) }
        launch { segments = container.youtubeWatch.skipSegments(videoId, sponsorCats) }
        launch { inLater = container.videoLibrary.isInWatchLater(videoId) }
        launch { liked = container.videoLibrary.isLiked(videoId) }
        runCatching { YouTubeVideo.options(videoId) }
            .onSuccess { o ->
                options = o
                container.videoLibrary.recordWatch(
                    VideoItem(o.videoId, o.title, o.uploader, o.channelUrl, o.thumbnailUrl, o.durationSec, o.viewCount, o.uploaded, o.channelAvatar, o.isLive),
                    o.related,
                )
                if (offlineFile == null) {
                    if (o.choices.isEmpty() && o.hlsUrl != null) {
                        runCatching {
                            player.setMediaSource(buildSource(container, null, null, o.hlsUrl))
                            player.prepare()
                            player.play()
                        }.onFailure { error = "Couldn't play this live stream" }
                    } else if (o.choices.isNotEmpty()) {
                        selected = o.pick(maxHeight)
                    } else error = "No playable video for this one"
                }
                if (captionPref.isNotBlank()) {
                    captionTrack = o.captions.firstOrNull { it.languageTag.startsWith(captionPref) && !it.autoGenerated }
                        ?: o.captions.firstOrNull { it.languageTag.startsWith(captionPref) }
                }
            }
            .onFailure { if (offlineFile == null) error = it.message ?: "Couldn't load video" }
        // Get the next video ready so it starts quickly.
        launch {
            delay(4_000)
            (VideoQueue.after(videoId).firstOrNull() ?: options?.related?.firstOrNull { !it.isShort })
                ?.let { YouTubeVideo.prefetch(it.id) }
        }
        // Load the first comments straight away for the comments card, like YouTube.
        launch {
            delay(800)
            val p = container.youtubeWatch.comments(videoId)
            commentsPager = p
            commentsLoading = true
            runCatching { p.load() }.onSuccess { comments = it }.onFailure { commentsError = it.message ?: "Comments are turned off" }
            commentsLoading = false
        }
    }

    var firstChoice by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        val choice = selected ?: return@LaunchedEffect
        val pos = if (firstChoice) (container.videoLibrary.resumeAt(videoId) ?: 0L) else player.currentPosition
        firstChoice = false
        runCatching {
            player.setMediaSource(buildSource(container, choice, null, null), pos)
            player.prepare()
            player.play()
        }.onFailure { error = it.message }
        if (pos > 0) seekFlash = "Carrying on from ${clock(pos)}"
    }

    LaunchedEffect(captionTrack) {
        cues = emptyList()
        val c = captionTrack ?: return@LaunchedEffect
        runCatching { container.youtubeWatch.captions(c.url) }
            .onSuccess { cues = it }
            .onFailure { Toast.makeText(context, "Couldn't load captions", Toast.LENGTH_SHORT).show() }
    }

    // Ticker: position, captions, SponsorBlock skips, saving progress.
    LaunchedEffect(Unit) {
        val skipped = HashSet<Long>()
        var lastSave = 0L
        while (true) {
            val pos = player.currentPosition
            position = pos
            buffered = player.bufferedPosition
            if (player.duration > 0) duration = player.duration
            captionText = cues.lastOrNull { pos >= it.startMs && pos < it.endMs }?.text.orEmpty()
            if (player.isPlaying) {
                segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 500 && it.startMs !in skipped }?.let { s ->
                    skipped += s.startMs
                    player.seekTo(s.endMs)
                    seekFlash = "Skipped ${s.label}"
                }
                if (System.currentTimeMillis() - lastSave > 5000) { lastSave = System.currentTimeMillis(); saveProgress() }
            }
            delay(200)
        }
    }

    // Controls fade away after 3 seconds of playing, like YouTube.
    LaunchedEffect(controlsVisible, isPlaying, touchedAt) {
        if (controlsVisible && isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(900); seekFlash = null } }

    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0))
        seekFlash = if (ms > 0) "+${ms / 1000} seconds" else "${ms / 1000} seconds"
    }

    fun togglePlay() {
        when {
            ended -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
        poke()
    }

    // ---------------------------------------------------------------- fullscreen

    fun applySystemBars(full: Boolean) {
        val act = activity ?: return
        val window = act.window
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (full) {
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (full) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

    fun setFullscreen(on: Boolean, fromButton: Boolean) {
        fullscreen = on
        if (!on) zoomFill = false
        applySystemBars(on)
        if (fromButton) {
            fullscreenFromRotation = false
            activity?.requestedOrientation =
                if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    LaunchedEffect(orientation) {
        val landscape = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscape && !fullscreen) {
            fullscreenFromRotation = true
            setFullscreen(true, fromButton = false)
        } else if (!landscape && fullscreen && fullscreenFromRotation) {
            fullscreenFromRotation = false
            setFullscreen(false, fromButton = false)
        }
    }
    androidx.activity.compose.BackHandler(enabled = fullscreen && !minimized) { setFullscreen(false, fromButton = true) }
    androidx.activity.compose.BackHandler(enabled = !fullscreen && panel != null && !minimized) { panel = null }

    // Dragging the video down: how far (pixels), and how far it takes to shrink completely.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dragAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragMaxPx = with(density) { (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.55f).dp.toPx() }

    fun minimizeNow() {
        if (fullscreen) setFullscreen(false, fromButton = true)
        panel = null
        onMinimize()
    }
    DisposableEffect(Unit) { onDispose { applySystemBars(false) } }

    // ---------------------------------------------------------------- the video with its controls

    val playerArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    val pv = PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        playerView = this
                    }
                    app.mixtape.player.VideoGestureFrame(
                        ctx,
                        onZoom = { fill ->
                            if (fill != zoomFill) { zoomFill = fill; seekFlash = if (fill) "Zoomed to fill" else "Original size" }
                        },
                        onDoubleTapSide = { forward -> seekBy(if (forward) 10_000 else -10_000) },
                        onSingleTap = { if (controlsVisible) controlsVisible = false else poke() },
                        onDrag = { dy -> if (!fullscreen) scope.launch { dragAnim.snapTo(dy) } },
                        onDragEnd = { dy, vy ->
                            if (fullscreen) {
                                // In full screen a drag down just leaves full screen.
                                if (dy > 120 * density.density || vy > 1500f) setFullscreen(false, fromButton = true)
                            } else scope.launch {
                                if (dy > dragMaxPx * 0.25f || vy > 1500f) {
                                    // Far enough (or flicked): finish the shrink into the corner.
                                    dragAnim.animateTo(dragMaxPx, androidx.compose.animation.core.tween(160))
                                    minimizeNow()
                                    dragAnim.snapTo(0f)
                                } else {
                                    dragAnim.animateTo(0f, androidx.compose.animation.core.spring())
                                }
                            }
                        },
                    ).apply { addView(pv) }
                },
                update = {
                    playerView?.resizeMode = if (zoomFill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier.fillMaxSize(),
            )

            // The thumbnail fills the wait while the video loads.
            val thumb = options?.thumbnailUrl ?: peek?.thumbnail
            if (thumb != null && offlineFile == null && error == null && (options == null || (buffering && position == 0L))) {
                AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color.Black))
            }

            if (captionText.isNotBlank()) {
                Text(
                    captionText,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = if (fullscreen) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = if (controlsVisible && !inPip) 44.dp else 10.dp, start = 16.dp, end = 16.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            if (!inPip && controlsVisible) {
                // A dim layer that lets taps through to the video (no click handler on it).
                Box(Modifier.fillMaxSize().background(Color(0x73000000)))

                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { minimizeNow() }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = autoplay,
                        onCheckedChange = { container.settings.setVideoAutoplay(it); seekFlash = if (it) "Autoplay is on" else "Autoplay is off"; poke() },
                        modifier = Modifier.scale(0.7f),
                    )
                    CastButton()
                    val o = options
                    if (o != null && o.captions.isNotEmpty()) {
                        IconButton(onClick = {
                            poke()
                            if (captionTrack != null) {
                                captionTrack = null
                                container.settings.setCaptionsLanguage("")
                                seekFlash = "Captions off"
                            } else {
                                val c = o.captions.firstOrNull { it.languageTag.startsWith(captionPref.ifBlank { "en" }) } ?: o.captions.first()
                                captionTrack = c
                                container.settings.setCaptionsLanguage(c.languageTag.substringBefore('-'))
                                seekFlash = "Captions: ${c.name}"
                            }
                        }) {
                            Icon(if (captionTrack != null) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled, "Captions", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { showSettings = true; poke() }) { Icon(Icons.Filled.Settings, "Settings", tint = Color.White) }
                }

                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { player.seekTo(0); poke() }) {
                        Icon(Icons.Filled.SkipPrevious, "Start again", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    if (buffering && !ended) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    } else {
                        IconButton(onClick = { togglePlay() }, modifier = Modifier.size(64.dp)) {
                            Icon(
                                when { ended -> Icons.Filled.Replay; isPlaying -> Icons.Filled.Pause; else -> Icons.Filled.PlayArrow },
                                "Play / pause", tint = Color.White, modifier = Modifier.size(52.dp),
                            )
                        }
                    }
                    IconButton(onClick = { upNext.firstOrNull()?.let { openVideo(it.id) } }, enabled = upNext.isNotEmpty()) {
                        Icon(Icons.Filled.SkipNext, "Next video", tint = if (upNext.isNotEmpty()) Color.White else Color.Gray, modifier = Modifier.size(34.dp))
                    }
                }

                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (options?.isLive == true) "LIVE" else "${clock(position)} / ${clock(duration)}",
                        color = Color.White, style = MaterialTheme.typography.labelMedium,
                    )
                    if (currentChapter != null) {
                        Row(
                            Modifier.weight(1f).clickable { if (fullscreen) setFullscreen(false, true); panel = Panel.CHAPTERS },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("  ·  ${currentChapter.title}", color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = Color.White, modifier = Modifier.size(12.dp).padding(start = 2.dp))
                        }
                    } else Spacer(Modifier.weight(1f))
                    IconButton(onClick = { setFullscreen(!fullscreen, fromButton = true) }) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Full screen", tint = Color.White)
                    }
                }
            }

            seekFlash?.let {
                Text(
                    it, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.Center).padding(top = if (controlsVisible) 110.dp else 0.dp)
                        .background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            if (options == null && offlineFile == null && error == null && !controlsVisible) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            error?.let {
                Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }

            // YouTube's red bar along the bottom edge: thin while watching, grab-able with the controls up.
            if (!inPip && options?.isLive != true) {
                SeekBar(
                    position = position,
                    buffered = buffered,
                    duration = duration,
                    chapters = chapters,
                    segments = segments,
                    expanded = controlsVisible,
                    onSeek = { player.seekTo(it); poke() },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
    }

    if (inPip) {
        playerArea(Modifier.fillMaxSize())
        return
    }

    // Shrunk: a small window in the corner that keeps playing while you scroll the app.
    if (minimized) {
        Box(Modifier.fillMaxSize()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                shadowElevation = 10.dp,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 8.dp, bottom = miniBottomPadding)
                    .width(230.dp)
                    .aspectRatio(16f / 9f),
            ) {
                Box {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = false
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Tap the window to open it full size again.
                    Box(Modifier.fillMaxSize().clickable { onExpand() })
                    Row(Modifier.fillMaxWidth().background(Color(0x55000000)), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { togglePlay() }) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play / pause", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    }
                    if (duration > 0) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color(0x55FFFFFF))) {
                            Box(Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).height(2.dp).background(Color(0xFFFF0000)))
                        }
                    }
                }
            }
        }
        return
    }

    // While you drag it down, the whole page shrinks towards the bottom-right corner and the
    // screen underneath shows through, like YouTube.
    val dragP = (dragAnim.value / dragMaxPx).coerceIn(0f, 1f)
    val pageBg = MaterialTheme.colorScheme.background
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val sc = 1f - 0.62f * dragP
                scaleX = sc
                scaleY = sc
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.97f, 0.92f)
            }
            .background(pageBg.copy(alpha = 1f - dragP))
            .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars)),
    ) {
        playerArea(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        if (!fullscreen) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - dragP * 2.5f).coerceIn(0f, 1f) }) {
                when (panel) {
                    null -> Details(
                        options = options,
                        peek = peek,
                        videoItem = videoItem,
                        votes = votes,
                        liked = liked,
                        inLater = inLater,
                        subscribed = subscribed,
                        downloadedNow = videoKey in downloaded,
                        offline = offlineFile != null,
                        comments = comments,
                        commentsLoading = commentsLoading,
                        commentsError = commentsError,
                        segments = segments,
                        upNext = upNext,
                        historyProgress = { id -> history.firstOrNull { it.video.id == id }?.progress ?: 0f },
                        onDescription = { panel = Panel.DESCRIPTION },
                        onComments = { panel = Panel.COMMENTS },
                        onChannel = openChannel,
                        onSubscribe = { id, name, avatar ->
                            scope.launch {
                                if (id in subscribed) container.subscriptions.unsubscribe(id)
                                else container.subscriptions.subscribe(app.mixtape.source.Channel(id, name, avatar))
                            }
                        },
                        onLike = {
                            videoItem?.let { v ->
                                scope.launch {
                                    liked = container.videoLibrary.toggleLiked(v)
                                    seekFlash = if (liked) "Added to Liked videos" else "Removed from Liked videos"
                                    // Like it on YouTube too when you're signed in.
                                    if (container.youtubeAccount.signedIn.value) {
                                        runCatching { container.youtubeAccount.like(v.id, liked) }
                                    }
                                }
                            }
                        },
                        onSave = {
                            videoItem?.let { v ->
                                scope.launch {
                                    inLater = container.videoLibrary.toggleWatchLater(v)
                                    Toast.makeText(context, if (inLater) "Saved to Watch later" else "Removed from Watch later", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onShare = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$videoId"),
                                    "Share video",
                                )
                            )
                        },
                        onDownload = { track?.let { actions.downloadVideo(it) } },
                        onSaveToPhone = {
                            scope.launch {
                                val file = container.downloads.completedFile(videoKey) ?: return@launch
                                runCatching { VideoExport.saveToPhone(context, file, container.crypto.dataKey, options?.title ?: videoId) }
                                    .onSuccess { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                    .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                        },
                        onListen = {
                            track?.let {
                                val pos = player.currentPosition
                                player.pause()
                                container.player.playTracks(listOf(it), 0, startPositionMs = pos)
                            }
                        },
                        onPip = { activity?.let { if (Pip.supported(it)) Pip.enter(it) } },
                        castConnected = castConnected,
                        onPlayOnTv = {
                            track?.let {
                                player.pause()
                                container.player.playTracks(listOf(it.copy(source = SourceType.YOUTUBE_VIDEO)), 0)
                                Toast.makeText(context, "Playing on your TV", Toast.LENGTH_SHORT).show()
                            }
                        },
                        openVideo = openVideo,
                    )
                    Panel.CHAPTERS -> ChaptersPanel(
                        chapters, options?.thumbnailUrl, currentChapter,
                        onSeek = { player.seekTo(it * 1000L); player.play() },
                        onClose = { panel = null },
                    )
                    Panel.COMMENTS -> CommentsPanel(
                        videoId = videoId,
                        comments = comments,
                        loading = commentsLoading,
                        error = commentsError,
                        canLoadMore = commentsPager?.done == false,
                        onLoadMore = {
                            val p = commentsPager
                            if (p != null && !commentsLoading) {
                                commentsLoading = true
                                scope.launch {
                                    runCatching { p.load() }.onSuccess { comments = comments + it }
                                    commentsLoading = false
                                }
                            }
                        },
                        onClose = { panel = null },
                    )
                    Panel.DESCRIPTION -> DescriptionPanel(
                        options = options,
                        votes = votes,
                        chapters = chapters,
                        onSeek = { player.seekTo(it); player.play() },
                        onClose = { panel = null },
                    )
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            val o = options
            Column(Modifier.padding(bottom = 24.dp)) {
                if (o != null && offlineFile == null && o.choices.isNotEmpty()) {
                    Text("Quality", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        o.choices.forEach { c ->
                            androidx.compose.material3.FilterChip(
                                selected = selected == c,
                                onClick = { selected = c },
                                label = { Text(if (c.height >= 1440) "${c.label} · ${c.codec.label}" else c.label) },
                            )
                        }
                    }
                } else if (offlineFile != null) {
                    Text("Playing your downloaded copy", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
                }
                ListItem(
                    headlineContent = { Text("Playback speed") },
                    trailingContent = { Text(formatSpeed(videoSpeed)) },
                    modifier = Modifier.clickable { showSettings = false; showSpeed = true },
                )
                if (o != null && o.captions.isNotEmpty()) {
                    Text("Captions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(selected = captionTrack == null, onClick = {
                            captionTrack = null; container.settings.setCaptionsLanguage("")
                        }, label = { Text("Off") })
                        o.captions.forEach { c ->
                            androidx.compose.material3.FilterChip(selected = captionTrack == c, onClick = {
                                captionTrack = c; container.settings.setCaptionsLanguage(c.languageTag.substringBefore('-'))
                            }, label = { Text(c.name + if (c.autoGenerated) " (auto)" else "") })
                        }
                    }
                }
                ListItem(
                    headlineContent = { Text("Autoplay next video") },
                    trailingContent = { Switch(checked = autoplay, onCheckedChange = { container.settings.setVideoAutoplay(it) }) },
                )
                ListItem(
                    headlineContent = { Text("Loop this video") },
                    trailingContent = { Switch(checked = loop, onCheckedChange = { loop = it }) },
                )
                ListItem(
                    headlineContent = { Text("Background play") },
                    supportingContent = { Text("Keep the sound going when you leave the app") },
                    trailingContent = { Switch(checked = backgroundPlay, onCheckedChange = { container.settings.setVideoBackgroundPlay(it) }) },
                )
                ListItem(
                    headlineContent = { Text("Zoom to fill") },
                    supportingContent = { Text("Or pinch the video") },
                    trailingContent = { Switch(checked = zoomFill, onCheckedChange = { zoomFill = it }) },
                )
            }
        }
    }

    if (showSpeed) {
        SpeedSheet(
            speed = videoSpeed,
            keepPitch = videoKeepPitch,
            onSpeed = { videoSpeed = it },
            onKeepPitch = { videoKeepPitch = it },
            onDismiss = { showSpeed = false },
        )
    }
}

/** YouTube's progress bar: red played part, grey buffered part, gaps between chapters, yellow sponsor parts. */
@Composable
private fun SeekBar(
    position: Long,
    buffered: Long,
    duration: Long,
    chapters: List<VideoChapter>,
    segments: List<SkipSegment>,
    expanded: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val red = Color(0xFFFF0000)
    BoxWithConstraints(
        modifier
            .height(if (expanded) 24.dp else 3.dp)
            .then(
                if (expanded && duration > 0) Modifier
                    .pointerInput(duration) {
                        detectTapGestures { o -> onSeek((o.x / size.width * duration).toLong()) }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { dragFraction?.let { onSeek((it * duration).toLong()) }; dragFraction = null },
                            onDragCancel = { dragFraction = null },
                        ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
                    }
                else Modifier
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (expanded) 24.dp else 3.dp)) {
            val barH = if (expanded) 4.dp.toPx() else size.height
            val y = size.height - barH - if (expanded) 8.dp.toPx() else 0f
            val w = size.width
            val played = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val buf = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
            drawRect(Color(0x55FFFFFF), Offset(0f, y), Size(w, barH))
            drawRect(Color(0x99FFFFFF), Offset(0f, y), Size(w * buf, barH))
            drawRect(red, Offset(0f, y), Size(w * played, barH))
            if (duration > 0) {
                segments.forEach { s ->
                    val x0 = s.startMs.toFloat() / duration * w
                    val x1 = s.endMs.toFloat() / duration * w
                    drawRect(Color(0xFFFFD600), Offset(x0, y), Size((x1 - x0).coerceAtLeast(2f), barH))
                }
                chapters.drop(1).forEach { c ->
                    val x = c.startSec * 1000f / duration * w
                    drawRect(Color.Black, Offset(x - 1.5f, y), Size(3f, barH))
                }
            }
            if (expanded) drawCircle(red, radius = 7.dp.toPx(), center = Offset(w * played, y + barH / 2))
        }
    }
}

/** Everything under the video when no panel is open. */
@Composable
private fun Details(
    options: VideoOptions?,
    peek: VideoItem?,
    videoItem: VideoItem?,
    votes: Pair<Long, Long>?,
    liked: Boolean,
    inLater: Boolean,
    subscribed: Set<String>,
    downloadedNow: Boolean,
    offline: Boolean,
    comments: List<Comment>,
    commentsLoading: Boolean,
    commentsError: String?,
    segments: List<SkipSegment>,
    upNext: List<VideoItem>,
    historyProgress: (String) -> Float,
    onDescription: () -> Unit,
    onComments: () -> Unit,
    onChannel: (String) -> Unit,
    onSubscribe: (String, String, String?) -> Unit,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onSaveToPhone: () -> Unit,
    onListen: () -> Unit,
    onPip: () -> Unit,
    castConnected: Boolean,
    onPlayOnTv: () -> Unit,
    openVideo: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        val o = options
        if (o == null) {
            val p = peek
            if (p != null) item {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(p.channel, viewsText(p.views), p.ago).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        item {
            Column(Modifier.fillMaxWidth().clickable(onClick = onDescription).padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                Text(o.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val tags = remember(o.description) {
                    Regex("#\\w+").findAll(YouTubeWatch.htmlToText(o.description)).map { it.value }.distinct().take(2).joinToString(" ")
                }
                Text(
                    buildAnnotatedString {
                        append(listOf(if (o.isLive) "LIVE" else "", viewsText(o.viewCount), o.uploaded, tags).filter { it.isNotBlank() }.joinToString("  "))
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append("  ...more") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Channel row with the white Subscribe pill
        item {
            val channelId = o.channelUrl?.let { app.mixtape.source.SubscriptionRepository.channelIdFrom(it) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(enabled = channelId != null) { channelId?.let(onChannel) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelAvatar(o.channelAvatar, 36)
                    Spacer(Modifier.width(10.dp))
                    Text(o.uploader, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (o.subscriberCount > 0) {
                        Text("  " + compactCount(o.subscriberCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (channelId != null) {
                    val isSub = channelId in subscribed
                    Surface(
                        onClick = { onSubscribe(channelId, o.uploader, o.channelAvatar) },
                        shape = RoundedCornerShape(50),
                        color = if (isSub) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(
                            if (isSub) "Subscribed" else "Subscribe",
                            color = if (isSub) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
        // Action pills
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val likes = votes?.first?.takeIf { it >= 0 } ?: o.likeCount
                val dislikes = votes?.second ?: -1
                Pill(onLike) {
                    Icon(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Like", Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (likes >= 0) compactCount(likes + if (liked) 1 else 0) else "Like", style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outline))
                    Icon(Icons.Outlined.ThumbDown, "Dislikes", Modifier.size(18.dp))
                    if (dislikes >= 0) { Spacer(Modifier.width(6.dp)); Text(compactCount(dislikes), style = MaterialTheme.typography.labelLarge) }
                }
                PillIcon(Icons.AutoMirrored.Filled.Reply, "Share", onShare)
                PillIcon(if (inLater) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (inLater) "Saved" else "Save", onSave)
                if (downloadedNow) {
                    PillIcon(Icons.Filled.DownloadDone, if (offline) "Downloaded" else "Downloaded", onSaveToPhone)
                    PillIcon(Icons.Filled.SaveAlt, "Save to phone", onSaveToPhone)
                } else {
                    PillIcon(Icons.Filled.Download, "Download", onDownload)
                }
                PillIcon(Icons.Filled.Headphones, "Listen", onListen)
                PillIcon(Icons.Filled.PictureInPictureAlt, "Pop out", onPip)
                if (castConnected) PillIcon(Icons.Filled.Cast, "Play on TV", onPlayOnTv)
            }
        }
        // Comments card with the top comment
        item {
            Surface(
                onClick = onComments,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Comments", style = MaterialTheme.typography.titleSmall)
                    val first = comments.firstOrNull()
                    when {
                        first != null -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                if (first.avatar != null) AsyncImage(model = first.avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(first.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        commentsLoading -> Text("Loading...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        commentsError != null -> Text("Comments are turned off", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        else -> Text("Tap to read the comments", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (segments.isNotEmpty()) {
            item {
                Text(
                    "SponsorBlock skips ${segments.size} part${if (segments.size == 1) "" else "s"} (${segments.map { it.label }.distinct().joinToString(", ")}), shown in yellow on the bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        if (VideoQueue.after(o.videoId).isNotEmpty()) {
            item { Text("Up next in ${VideoQueue.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp)) }
        }
        items(upNext, key = { "next-" + it.id }) { v ->
            VideoCard(v, historyProgress(v.id), onOpen = { openVideo(v.id) }, onChannel = v.channelId?.let { id -> { onChannel(id) } })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit, subtitle: String? = null) {
    Column {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
        }
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ChaptersPanel(
    chapters: List<VideoChapter>,
    fallbackThumb: String?,
    current: VideoChapter?,
    onSeek: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Chapters", onClose)
        LazyColumn(Modifier.fillMaxSize()) {
            items(chapters, key = { "ch" + it.startSec }) { c ->
                val isCurrent = c == current
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onSeek(c.startSec) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        val thumb = c.thumbnail ?: fallbackThumb
                        if (thumb != null) AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatDuration(c.startSec.toLong()).ifEmpty { "0:00" },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentsPanel(
    videoId: String,
    comments: List<Comment>,
    loading: Boolean,
    error: String?,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (canLoadMore && last >= listState.layoutInfo.totalItemsCount - 3) onLoadMore() }
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Comments", onClose)
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (error != null && comments.isEmpty()) item { Text("Comments are turned off for this video", modifier = Modifier.padding(16.dp)) }
            items(comments) { c -> CommentRow(c, videoId) }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(24.dp))
                        canLoadMore -> TextButton(onClick = onLoadMore) { Text("More comments") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DescriptionPanel(
    options: VideoOptions?,
    votes: Pair<Long, Long>?,
    chapters: List<VideoChapter>,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val o = options ?: return
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Description", onClose)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Text(o.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val likes = votes?.first?.takeIf { it >= 0 } ?: o.likeCount
                    Stat(if (likes >= 0) compactCount(likes) else "-", "Likes")
                    Stat(if (o.viewCount >= 0) java.text.NumberFormat.getIntegerInstance().format(o.viewCount) else "-", "Views")
                    Stat(o.uploaded.ifBlank { "-" }, "Uploaded")
                }
                // Timestamps like 1:23 in the description jump to that moment.
                val text = remember(o.description) { YouTubeWatch.htmlToText(o.description) }
                val timeRe = remember { Regex("\\b(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\b") }
                val annotated = remember(text) {
                    buildAnnotatedString {
                        var last = 0
                        timeRe.findAll(text).forEach { m ->
                            append(text.substring(last, m.range.first))
                            val h = m.groupValues[1].toLongOrNull() ?: 0
                            val ms = ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000
                            pushStringAnnotation("t", ms.toString())
                            withStyle(SpanStyle(color = Color(0xFF3EA6FF))) { append(m.value) }
                            pop()
                            last = m.range.last + 1
                        }
                        append(text.substring(last))
                    }
                }
                ClickableText(
                    annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                ) { offset ->
                    annotated.getStringAnnotations("t", offset, offset).firstOrNull()?.let { onSeek(it.item.toLong()) }
                }
            }
            if (chapters.isNotEmpty()) {
                item { Text("Chapters", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) }
                itemsIndexed(chapters) { _, c ->
                    Row(Modifier.fillMaxWidth().clickable { onSeek(c.startSec * 1000L) }.padding(vertical = 8.dp)) {
                        Text(formatDuration(c.startSec.toLong()).ifEmpty { "0:00" }, color = Color(0xFF3EA6FF), modifier = Modifier.width(64.dp))
                        Text(c.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommentRow(c: Comment, videoId: String, isReply: Boolean = false) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var replies by remember { mutableStateOf<List<Comment>?>(null) }
    var repliesLoading by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Box(Modifier.size(if (isReply) 24.dp else 32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (c.avatar != null) AsyncImage(model = c.avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(if (c.pinned) "Pinned" else null, c.author, c.ago).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(c.text, maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Outlined.ThumbUp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" " + (if (c.likes > 0) compactCount(c.likes.toLong()) else ""), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Outlined.ThumbDown, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.hearted) Text("   ♥ by creator", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                val page = c.repliesPage
                if (!isReply && c.replies > 0 && page != null) {
                    TextButton(onClick = {
                        if (replies != null) replies = null else {
                            repliesLoading = true
                            scope.launch {
                                replies = runCatching { container.youtubeWatch.replies(videoId, page) }.getOrDefault(emptyList())
                                repliesLoading = false
                            }
                        }
                    }, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            when {
                                repliesLoading -> "Loading replies..."
                                replies != null -> "Hide replies"
                                else -> "${c.replies} ${if (c.replies == 1) "reply" else "replies"}"
                            },
                            color = Color(0xFF3EA6FF),
                        )
                    }
                }
            }
        }
        replies?.forEach { r -> Box(Modifier.padding(start = 40.dp)) { CommentRow(r, videoId, isReply = true) } }
    }
}
