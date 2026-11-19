package app.mixtape.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mixtape.core.model.formatDuration
import app.mixtape.source.VideoItem
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

fun viewsText(n: Long): String = when {
    n < 0 -> ""
    n >= 1_000_000_000 -> "%.1fB views".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM views".format(n / 1e6)
    n >= 1_000 -> "%.1fK views".format(n / 1e3)
    else -> "$n views"
}.replace(".0", "")

@Composable
private fun Thumbnail(v: VideoItem, progress: Float, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (v.thumbnail != null) {
            AsyncImage(model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        val badge = when {
            v.isLive -> "LIVE"
            v.durationSec > 0 -> formatDuration(v.durationSec)
            else -> null
        }
        if (badge != null) {
            Text(
                badge,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(if (v.isLive) Color(0xFFCC0000) else Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (progress > 0.02f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Color(0xFFFF0000)))
            }
        }
    }
}

/** Menu shared by both card styles: Watch later, listen as audio, download. */
@Composable
private fun VideoMenu(v: VideoItem, extra: List<Pair<String, () -> Unit>>) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Save to Watch later") }, onClick = {
                open = false
                scope.launch {
                    val added = container.videoLibrary.toggleWatchLater(v)
                    android.widget.Toast.makeText(context, if (added) "Saved to Watch later" else "Removed from Watch later", android.widget.Toast.LENGTH_SHORT).show()
                }
            })
            DropdownMenuItem(text = { Text("Listen as audio") }, onClick = { open = false; actions.play(listOf(v.toTrack()), 0) })
            DropdownMenuItem(text = { Text("Download video") }, onClick = { open = false; actions.downloadVideo(v.toTrack()) })
            DropdownMenuItem(text = { Text("Download as song") }, onClick = { open = false; actions.download(listOf(v.toTrack())) })
            extra.forEach { (label, f) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; f() }) }
        }
    }
}

/** Full-width card like YouTube's home feed. */
@Composable
fun VideoCard(
    v: VideoItem,
    progress: Float,
    onOpen: () -> Unit,
    onChannel: (() -> Unit)?,
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(bottom = 14.dp)) {
        Thumbnail(v, progress, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Row(Modifier.padding(start = 12.dp, top = 10.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier),
            ) {
                if (v.avatar != null) AsyncImage(model = v.avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOf(v.channel, viewsText(v.views), v.ago).filter { it.isNotBlank() }.joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier,
                )
            }
            VideoMenu(v, extraMenu)
        }
    }
}

/** Compact row (thumbnail on the left), for Up next, history and channel pages. */
@Composable
fun VideoRowItem(
    v: VideoItem,
    progress: Float,
    onOpen: () -> Unit,
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
    highlight: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (highlight) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Thumbnail(v, progress, Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(v.channel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val meta = listOf(viewsText(v.views), v.ago).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VideoMenu(v, extraMenu)
    }
}
