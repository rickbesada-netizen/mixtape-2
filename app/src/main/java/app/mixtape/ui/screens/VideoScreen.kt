package app.mixtape.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import app.mixtape.ui.components.VideoRowItem
import app.mixtape.ui.components.rememberCastConnected
import app.mixtape.ui.components.viewsText
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private fun buildSource(container: AppContainer, choice: VideoChoice?, offline: File?, hls: String?): MediaSource {
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

/**
 * The watch page: no ads, sponsor segments skipped (SponsorBlock), dislikes shown (Return YouTube
 * Dislike), captions, chapters, comments, up next with autoplay, resume where you left off,
 * background play, picture-in-picture, casting and downloads.
 */
@Composable
fun VideoScreen(
    videoId: String,
    onBack: () -> Unit,
    openChannel: (String) -> Unit = {},
    openVideo: (String) -> Unit = {},
) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoKey = Track.keyOf(SourceType.YOUTUBE_VIDEO, videoId)
    val maxHeight by container.settings.videoQuality.collectAsStateWithLifecycle()
    val hiResMp4 by container.settings.hiResAsMp4.collectAsStateWithLifecycle()
    val autoplay by container.settings.videoAutoplay.collectAsStateWithLifecycle()
    val sponsorCats by container.settings.sponsorCategories.collectAsStateWithLifecycle()
    val captionPref by container.settings.captionsLanguage.collectAsStateWithLifecycle()
    val doneFlow = remember { container.downloads.doneKeys() }
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())
    val history by container.videoLibrary.history.collectAsStateWithLifecycle()

    var options by remember { mutableStateOf<VideoOptions?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<VideoChoice?>(null) }
    var offlineFile by remember { mutableStateOf<File?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var votes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var segments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var captionTrack by remember { mutableStateOf<CaptionTrack?>(null) }
    var cues by remember { mutableStateOf<List<CaptionCue>>(emptyList()) }
    var captionText by remember { mutableStateOf("") }
    var showCaptionMenu by remember { mutableStateOf(false) }
    var descriptionOpen by remember { mutableStateOf(false) }
    var inLater by remember { mutableStateOf(false) }
    var commentsPager by remember { mutableStateOf<Pager<Comment>?>(null) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var handedOff by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(context).build() }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var videoSpeed by remember { mutableStateOf(1f) }
    var videoKeepPitch by remember { mutableStateOf(true) }
    var showSpeed by remember { mutableStateOf(false) }
    LaunchedEffect(videoSpeed, videoKeepPitch) {
        player.playbackParameters = speedParams(videoSpeed, videoKeepPitch)
    }

    val activity = context as? Activity
    val castConnected = rememberCastConnected()
    val inPip by Pip.inPip.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val track = options?.let {
        Track(SourceType.YOUTUBE, it.videoId, it.title, it.uploader, durationSec = it.durationSec, artworkUrl = it.thumbnailUrl)
    }
    val upNext: List<VideoItem> = remember(options) {
        val queued = VideoQueue.after(videoId)
        (queued + options?.related.orEmpty().filter { !it.isShort && !it.isLive }).distinctBy { it.id }.filter { it.id != videoId }
    }
    val currentUpNext by androidx.compose.runtime.rememberUpdatedState(upNext)
    val currentAutoplay by androidx.compose.runtime.rememberUpdatedState(autoplay)
    val currentTrack by androidx.compose.runtime.rememberUpdatedState(track)

    fun saveProgress() {
        val d = player.duration
        if (d > 0) container.videoLibrary.saveProgress(videoId, player.currentPosition, d)
    }

    DisposableEffect(Unit) {
        container.player.pause()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Pip.videoPlaying = isPlaying
                activity?.let { Pip.update(it) }
                if (!isPlaying) saveProgress()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
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
                    // Background play: hand the sound to the music player so it keeps going with
                    // the screen off. (Closing the floating window just stops.)
                    if (wasPlaying && t != null && !Pip.inPip.value && container.settings.videoBackgroundPlay.value) {
                        container.player.playTracks(listOf(t), 0, startPositionMs = player.currentPosition)
                        handedOff = true
                    }
                    player.pause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (handedOff) {
                        // Back in the app: carry on the video from where the sound got to.
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
        runCatching { YouTubeVideo.options(videoId) }
            .onSuccess { o ->
                options = o
                container.videoLibrary.recordWatch(
                    VideoItem(o.videoId, o.title, o.uploader, o.channelUrl, o.thumbnailUrl, o.durationSec, o.viewCount, o.uploaded, o.channelAvatar, o.isLive),
                    o.related,
                )
                if (offlineFile == null) {
                    if (o.choices.isEmpty() && o.hlsUrl != null) {
                        player.setMediaSource(buildSource(container, null, null, o.hlsUrl))
                        player.prepare()
                        player.play()
                    } else {
                        selected = o.pick(maxHeight)
                    }
                }
                // Captions: switch on your usual language if this video has it.
                if (captionPref.isNotBlank()) {
                    captionTrack = o.captions.firstOrNull { it.languageTag.startsWith(captionPref) && !it.autoGenerated }
                        ?: o.captions.firstOrNull { it.languageTag.startsWith(captionPref) }
                }
            }
            .onFailure { if (offlineFile == null) error = it.message ?: "Couldn't load video" }
        launch { votes = container.youtubeWatch.votes(videoId) }
        launch { segments = container.youtubeWatch.skipSegments(videoId, sponsorCats) }
        launch { inLater = container.videoLibrary.isInWatchLater(videoId) }
    }

    // Switching quality keeps the playback position (and the first load resumes where you were).
    var firstChoice by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        val choice = selected ?: return@LaunchedEffect
        val pos = if (firstChoice) (container.videoLibrary.resumeAt(videoId) ?: 0L) else player.currentPosition
        firstChoice = false
        player.setMediaSource(buildSource(container, choice, null, null), pos)
        player.prepare()
        player.play()
        if (pos > 0) Toast.makeText(context, "Carrying on from ${formatDuration(pos / 1000)}", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(captionTrack) {
        cues = emptyList()
        val c = captionTrack ?: return@LaunchedEffect
        runCatching { container.youtubeWatch.captions(c.url) }
            .onSuccess { cues = it }
            .onFailure { Toast.makeText(context, "Couldn't load captions", Toast.LENGTH_SHORT).show() }
    }

    // Position ticker: captions, SponsorBlock skips, progress saving.
    LaunchedEffect(Unit) {
        val skipped = HashSet<Long>()
        var lastSave = 0L
        while (true) {
            val pos = player.currentPosition
            position = pos
            captionText = cues.lastOrNull { pos >= it.startMs && pos < it.endMs }?.text.orEmpty()
            if (player.isPlaying) {
                segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 500 && it.startMs !in skipped }?.let { s ->
                    skipped += s.startMs
                    player.seekTo(s.endMs)
                    Toast.makeText(context, "Skipped ${s.label}", Toast.LENGTH_SHORT).show()
                }
                if (System.currentTimeMillis() - lastSave > 5000) { lastSave = System.currentTimeMillis(); saveProgress() }
            }
            delay(200)
        }
    }

    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(600); seekFlash = null } }

    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0))
        seekFlash = if (ms > 0) "+${ms / 1000}s" else "${ms / 1000}s"
    }

    val videoBox: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        setFullscreenButtonClickListener { isFull ->
                            fullscreen = isFull
                            (ctx as? Activity)?.requestedOrientation =
                                if (isFull) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        playerView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!inPip) {
                // Double-tap the left / right side to skip 10 seconds, like YouTube.
                Row(Modifier.fillMaxSize().padding(bottom = 56.dp)) {
                    Box(Modifier.weight(0.35f).fillMaxHeight().pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { seekBy(-10_000) },
                            onTap = { playerView?.let { if (it.isControllerFullyVisible) it.hideController() else it.showController() } },
                        )
                    })
                    Spacer(Modifier.weight(0.3f))
                    Box(Modifier.weight(0.35f).fillMaxHeight().pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { seekBy(10_000) },
                            onTap = { playerView?.let { if (it.isControllerFullyVisible) it.hideController() else it.showController() } },
                        )
                    })
                }
            }
            if (captionText.isNotBlank()) {
                Text(
                    captionText,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = if (fullscreen) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (inPip) 4.dp else 48.dp, start = 16.dp, end = 16.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            seekFlash?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (options == null && offlineFile == null && error == null) CircularProgressIndicator()
            error?.let { Text(it, color = Color.White, modifier = Modifier.padding(24.dp)) }
        }
    }

    // In the floating window, show only the picture (and captions).
    if (inPip) {
        videoBox(Modifier.fillMaxSize())
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(options?.title ?: "Video", style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                val o = options
                if (o != null && o.captions.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { showCaptionMenu = true }) {
                            Icon(
                                Icons.Filled.ClosedCaption, "Captions",
                                tint = if (captionTrack != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = showCaptionMenu, onDismissRequest = { showCaptionMenu = false }) {
                            DropdownMenuItem(text = { Text("Off") }, onClick = {
                                showCaptionMenu = false; captionTrack = null; container.settings.setCaptionsLanguage("")
                            })
                            o.captions.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name + if (c.autoGenerated) " (auto)" else "") },
                                    onClick = {
                                        showCaptionMenu = false
                                        captionTrack = c
                                        container.settings.setCaptionsLanguage(c.languageTag.substringBefore('-'))
                                    },
                                )
                            }
                        }
                    }
                }
                if (activity != null && Pip.supported(activity)) {
                    IconButton(onClick = { Pip.enter(activity) }) { Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture") }
                }
                CastButton()
            }
        }
        videoBox(Modifier.fillMaxWidth().then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f)))

        if (!fullscreen) LazyColumn(Modifier.fillMaxSize()) {
            val o = options
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (o != null) {
                        Text(o.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOf(
                                if (o.isLive) "LIVE" else "",
                                viewsText(o.viewCount),
                                o.uploaded,
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Likes, dislikes, Watch later, share
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val likes = votes?.first?.takeIf { it >= 0 } ?: o.likeCount
                            val dislikes = votes?.second ?: -1
                            FilledTonalButton(onClick = {}) {
                                Icon(Icons.Filled.ThumbUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(if (likes >= 0) compactCount(likes) else "Like")
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.Filled.ThumbDown, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(if (dislikes >= 0) compactCount(dislikes) else "")
                            }
                            FilledTonalButton(onClick = {
                                scope.launch {
                                    inLater = container.videoLibrary.toggleWatchLater(
                                        VideoItem(o.videoId, o.title, o.uploader, o.channelUrl, o.thumbnailUrl, o.durationSec)
                                    )
                                    Toast.makeText(context, if (inLater) "Saved to Watch later" else "Removed from Watch later", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(if (inLater) Icons.Filled.WatchLater else Icons.Outlined.WatchLater, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text(if (inLater) "Saved" else "Watch later")
                            }
                            FilledTonalButton(onClick = {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${o.videoId}"),
                                        "Share video",
                                    )
                                )
                            }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                        }

                        // Channel + subscribe
                        val channelId = o.channelUrl?.let { app.mixtape.source.SubscriptionRepository.channelIdFrom(it) }
                        if (channelId != null) {
                            val subsFlow = remember { container.subscriptions.subscribedIds() }
                            val subbed by subsFlow.collectAsStateWithLifecycle(emptySet())
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable { openChannel(channelId) }, verticalAlignment = Alignment.CenterVertically) {
                                    ChannelAvatar(o.channelAvatar, 40)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(o.uploader, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                        if (o.subscriberCount > 0) {
                                            Text(compactCount(o.subscriberCount) + " subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (channelId in subbed) {
                                    OutlinedButton(onClick = { scope.launch { container.subscriptions.unsubscribe(channelId) } }) { Text("Subscribed") }
                                } else {
                                    Button(onClick = {
                                        scope.launch { container.subscriptions.subscribe(app.mixtape.source.Channel(channelId, o.uploader, o.channelAvatar)) }
                                    }) { Text("Subscribe") }
                                }
                            }
                        }

                        // Description + chapters
                        if (o.description.isNotBlank() || o.chapters.isNotEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 12.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .clickable { descriptionOpen = !descriptionOpen }
                                    .padding(12.dp),
                            ) {
                                val text = remember(o.description) { YouTubeWatch.htmlToText(o.description) }
                                Text(
                                    text, maxLines = if (descriptionOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!descriptionOpen) Text("more", style = MaterialTheme.typography.labelLarge)
                                if (descriptionOpen && o.chapters.isNotEmpty()) {
                                    Text("Chapters", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                                    o.chapters.forEach { (sec, title) ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable { player.seekTo(sec * 1000L); player.play() }.padding(vertical = 6.dp),
                                        ) {
                                            Text(formatDuration(sec.toLong()).ifEmpty { "0:00" }, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
                                            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }

                        if (segments.isNotEmpty()) {
                            Text(
                                "SponsorBlock will skip ${segments.size} part${if (segments.size == 1) "" else "s"} (${segments.map { it.label }.distinct().joinToString(", ")}).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }

                        Spacer(Modifier.size(12.dp))
                        if (offlineFile == null && o.choices.isNotEmpty()) {
                            Text("Quality", style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                o.choices.forEach { c ->
                                    FilterChip(
                                        selected = selected == c,
                                        onClick = { selected = c },
                                        label = { Text(if (c.height >= 1440) "${c.label} · ${c.codec.label}" else c.label) },
                                    )
                                }
                            }
                        } else if (offlineFile != null) {
                            Text("Playing your downloaded copy", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(Modifier.size(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showSpeed = true }) {
                            Icon(Icons.Filled.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Speed " + formatSpeed(videoSpeed))
                        }
                        val isDone = videoKey in downloaded
                        val dl = o?.pickDownload(maxHeight)
                        if (!isDone && track != null && dl != null) {
                            val format = if (dl.codec == app.mixtape.source.VideoCodec.VP9 && !hiResMp4) "WebM" else "MP4"
                            FilledTonalButton(onClick = { actions.downloadVideo(track) }) {
                                Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Download $format (${dl.label})")
                            }
                        }
                        if (isDone) {
                            FilledTonalButton(onClick = {
                                scope.launch {
                                    val file = container.downloads.completedFile(videoKey) ?: return@launch
                                    runCatching { VideoExport.saveToPhone(context, file, container.crypto.dataKey, options?.title ?: videoId) }
                                        .onSuccess { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                        .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
                                }
                            }) { Icon(Icons.Filled.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save to phone") }
                        }
                        if (track != null && castConnected) {
                            Button(onClick = {
                                player.pause()
                                container.player.playTracks(listOf(track.copy(source = SourceType.YOUTUBE_VIDEO)), 0)
                                Toast.makeText(context, "Playing on your TV", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Filled.Cast, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play on TV") }
                        }
                        if (track != null) {
                            FilledTonalButton(onClick = {
                                val pos = player.currentPosition
                                player.pause()
                                container.player.playTracks(listOf(track), 0, startPositionMs = pos)
                            }) { Icon(Icons.Filled.Headphones, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Listen as audio") }
                        }
                    }
                    if (error != null && offlineFile == null) TextButton(onClick = onBack) { Text("Go back") }
                }
                HorizontalDivider()
            }

            // Comments
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (commentsPager == null) {
                            TextButton(onClick = {
                                val p = container.youtubeWatch.comments(videoId)
                                commentsPager = p
                                commentsLoading = true
                                scope.launch {
                                    runCatching { p.load() }.onSuccess { comments = it }.onFailure { commentsError = it.message ?: "Comments are off" }
                                    commentsLoading = false
                                }
                            }) { Text("Show") }
                        }
                    }
                    commentsError?.let { Text("Couldn't load comments: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            items(comments) { c -> CommentRow(c) }
            if (commentsPager != null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        when {
                            commentsLoading -> CircularProgressIndicator(Modifier.size(24.dp))
                            commentsPager?.done == false -> TextButton(onClick = {
                                val p = commentsPager
                                if (p != null) {
                                    commentsLoading = true
                                    scope.launch {
                                        runCatching { p.load() }.onSuccess { comments = comments + it }
                                        commentsLoading = false
                                    }
                                }
                            }) { Text("More comments") }
                            comments.isEmpty() -> Text("No comments", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { HorizontalDivider() }

            // Up next
            if (upNext.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (VideoQueue.after(videoId).isNotEmpty()) "Up next in ${VideoQueue.title}" else "Up next",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                        Text("Autoplay", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = autoplay, onCheckedChange = { container.settings.setVideoAutoplay(it) })
                    }
                }
                items(upNext, key = { "next-" + it.id }) { v ->
                    val progress = history.firstOrNull { it.video.id == v.id }?.progress ?: 0f
                    VideoRowItem(v, progress, onOpen = { openVideo(v.id) })
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
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

@Composable
private fun CommentRow(c: Comment) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))) {
            if (c.avatar != null) AsyncImage(model = c.avatar, contentDescription = null, modifier = Modifier.fillMaxSize().background(Color.Transparent, RoundedCornerShape(16.dp)))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(if (c.pinned) "Pinned" else null, c.author, c.ago).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(c.text, maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Filled.ThumbUp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" " + (if (c.likes > 0) compactCount(c.likes.toLong()) else ""), style = MaterialTheme.typography.labelSmall)
                if (c.hearted) Text("  ♥ by creator", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (c.replies > 0) Text("  ${c.replies} replies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
