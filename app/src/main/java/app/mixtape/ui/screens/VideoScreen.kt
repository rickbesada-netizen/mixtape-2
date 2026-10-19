package app.mixtape.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Speed
import app.mixtape.player.formatSpeed
import app.mixtape.player.speedParams
import app.mixtape.ui.components.SpeedSheet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
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
import app.mixtape.source.VideoChoice
import app.mixtape.source.VideoOptions
import app.mixtape.source.YouTubeVideo
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import kotlinx.coroutines.launch
import java.io.File

private fun buildSource(container: AppContainer, choice: VideoChoice?, offline: File?): MediaSource {
    if (offline != null) {
        val factory = DataSource.Factory { EncryptedFileDataSource(container.crypto.dataKey) }
        return ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.fromFile(offline)))
    }
    requireNotNull(choice)
    val factory = DataSource.Factory {
        MixtapeDataSource(container.resolver, container.http, container.crypto.dataKey) { 0L }
    }
    val pf = ProgressiveMediaSource.Factory(factory)
    val video = pf.createMediaSource(MediaItem.fromUri(choice.videoUrl))
    val audio = choice.audioUrl
    return if (choice.muxed || audio == null) video
    else MergingMediaSource(video, pf.createMediaSource(MediaItem.fromUri(audio)))
}

@Composable
fun VideoScreen(videoId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoKey = Track.keyOf(SourceType.YOUTUBE_VIDEO, videoId)
    val maxHeight by container.settings.videoQuality.collectAsStateWithLifecycle()
    val hiResMp4 by container.settings.hiResAsMp4.collectAsStateWithLifecycle()
    val doneFlow = remember { container.downloads.doneKeys() }
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())

    var options by remember { mutableStateOf<VideoOptions?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<VideoChoice?>(null) }
    var offlineFile by remember { mutableStateOf<File?>(null) }
    var fullscreen by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(context).build() }
    var videoSpeed by remember { mutableStateOf(1f) }
    var videoKeepPitch by remember { mutableStateOf(true) }
    var showSpeed by remember { mutableStateOf(false) }
    LaunchedEffect(videoSpeed, videoKeepPitch) {
        player.playbackParameters = speedParams(videoSpeed, videoKeepPitch)
    }

    DisposableEffect(Unit) {
        container.player.pause()
        onDispose {
            player.release()
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(videoId) {
        offlineFile = container.downloads.completedFile(videoKey)
        if (offlineFile != null) {
            player.setMediaSource(buildSource(container, null, offlineFile))
            player.prepare()
            player.play()
        }
        runCatching { YouTubeVideo.options(videoId) }
            .onSuccess { o ->
                options = o
                if (offlineFile == null) selected = o.pick(maxHeight)
            }
            .onFailure { if (offlineFile == null) error = it.message ?: "Couldn't load video" }
    }

    // Switching quality keeps the playback position.
    LaunchedEffect(selected) {
        val choice = selected ?: return@LaunchedEffect
        val pos = player.currentPosition
        player.setMediaSource(buildSource(container, choice, null), pos)
        player.prepare()
        player.play()
    }

    val track = options?.let {
        Track(SourceType.YOUTUBE, it.videoId, it.title, it.uploader, durationSec = it.durationSec, artworkUrl = it.thumbnailUrl)
    }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    options?.title ?: "Video",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
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
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (options == null && offlineFile == null && error == null) CircularProgressIndicator()
            error?.let { Text(it, color = Color.White, modifier = Modifier.padding(24.dp)) }
        }

        if (!fullscreen) Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            val o = options
            if (o != null) {
                Text(o.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOf(o.uploader, formatDuration(o.durationSec)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                if (offlineFile == null) {
                    Text("Quality", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        o.choices.forEach { c ->
                            FilterChip(
                                selected = selected == c,
                                onClick = { selected = c },
                                label = { Text(if (c.height >= 1440) "${c.label} · ${c.codec.label}" else c.label) },
                            )
                        }
                    }
                } else {
                    Text("Playing your downloaded copy", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.size(12.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { showSpeed = true }) {
                    Icon(Icons.Filled.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Speed " + formatSpeed(videoSpeed))
                }
                val isDone = videoKey in downloaded
                val dl = o?.pickDownload(maxHeight)
                if (!isDone && track != null && dl != null) {
                    val format = if (dl.codec == app.mixtape.source.VideoCodec.VP9 && !hiResMp4) "WebM" else "MP4"
                    FilledTonalButton(onClick = { actions.downloadVideo(track) }) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Download $format (${dl.label})")
                    }
                }
                if (isDone) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val file = container.downloads.completedFile(videoKey) ?: return@launch
                            runCatching {
                                VideoExport.saveToPhone(context, file, container.crypto.dataKey, options?.title ?: videoId)
                            }.onSuccess { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }) {
                        Icon(Icons.Filled.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save to phone")
                    }
                }
                if (track != null) {
                    FilledTonalButton(onClick = {
                        player.pause()
                        actions.play(listOf(track), 0)
                    }) {
                        Icon(Icons.Filled.Headphones, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Listen as audio")
                    }
                }
            }
            if (error != null && offlineFile == null) {
                TextButton(onClick = onBack) { Text("Go back") }
            }
            Text(
                "Downloads are saved inside the app. Use \"Save to phone\" to copy one to your gallery. " +
                    "1440p and 4K need a phone that can decode VP9 or AV1, so those qualities only appear if yours can.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
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
