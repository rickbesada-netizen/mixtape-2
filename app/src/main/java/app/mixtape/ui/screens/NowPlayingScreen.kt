package app.mixtape.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.model.formatDuration
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import coil.compose.AsyncImage

@Composable
fun NowPlayingScreen(onBack: () -> Unit, onFixMatch: (String) -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    val likedFlow = remember { container.library.likedKeys() }
    val doneFlow = remember { container.downloads.doneKeys() }
    val liked by likedFlow.collectAsStateWithLifecycle(emptySet())
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())
    var showQueue by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val np = state.nowPlaying
    val track by produceState<Track?>(null, np?.key) {
        value = np?.key?.let { container.library.getTrack(it) }
    }

    if (np == null) {
        Box(Modifier.fillMaxSize().clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Text("Nothing playing")
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
            Spacer(Modifier.weight(1f))
            Text(
                if (showQueue) "Up next" else "Now playing",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showQueue = !showQueue }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue")
            }
        }

        if (showQueue) {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(state.queue) { i, item ->
                    val current = i == state.currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { player.skipTo(i) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(item.artworkUrl, 40)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!current) {
                            IconButton(onClick = { player.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove") }
                        }
                    }
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (np.artworkUrl != null) {
                        AsyncImage(
                            model = np.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (state.isBuffering) CircularProgressIndicator()
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    np.title, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    np.artist, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val t = track
            if (t != null) {
                val isLiked = t.key in liked
                IconButton(onClick = { actions.toggleLike(t, !isLiked) }) {
                    Icon(
                        if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like",
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val isDone = t.key in downloaded
                IconButton(enabled = !isDone, onClick = { actions.download(listOf(t)) }) {
                    Icon(if (isDone) Icons.Filled.DownloadDone else Icons.Filled.Download, "Download")
                }
            }
        }

        if (track?.source == SourceType.SPOTIFY) {
            TextButton(onClick = { onFixMatch(np.key) }) { Text("Wrong song? Fix the YouTube match") }
        }

        val duration = state.durationMs.coerceAtLeast(1)
        Slider(
            value = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                player.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            enabled = state.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            val shownPos = if (dragging) (dragValue * duration).toLong() else state.positionMs
            Text(formatDuration(shownPos / 1000).ifEmpty { "0:00" }, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(state.durationMs / 1000), style = MaterialTheme.typography.bodySmall)
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { player.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle, "Shuffle",
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { player.previous() }) {
                Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { player.togglePlay() },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play/pause", Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { player.next() }, enabled = state.hasNext) {
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp))
            }
            IconButton(onClick = { player.cycleRepeat() }) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repeat",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
