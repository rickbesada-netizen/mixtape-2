package app.mixtape.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mixtape.AppContainer
import app.mixtape.core.model.Track
import app.mixtape.core.model.formatDuration
import app.mixtape.core.model.display
import app.mixtape.data.db.PlaylistWithCount
import coil.compose.AsyncImage

val LocalContainer = compositionLocalOf<AppContainer> { error("No AppContainer") }

/** Everything a track row can do; implemented once in MixtapeAppUi. */
interface TrackActions {
    fun play(list: List<Track>, index: Int)
    fun playNext(track: Track)
    fun addToQueue(track: Track)
    fun toggleLike(track: Track, liked: Boolean)
    fun download(tracks: List<Track>)
    fun addToPlaylist(track: Track)
    fun fixMatch(track: Track)
    fun openLink(text: String)
    fun watchVideo(track: Track)
    fun downloadVideo(track: Track)
    fun openCollection(url: String)
}

val LocalTrackActions = compositionLocalOf<TrackActions> { error("No TrackActions") }

@Composable
fun Artwork(url: String?, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    liked: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
    extraMenu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val actions = LocalTrackActions.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUrl, 48)
        Spacer(Modifier.width(12.dp))
        val shown = remember(track) { track.display() }
        Column(Modifier.weight(1f)) {
            Text(
                shown.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                shown.artist.takeIf { it.isNotBlank() },
                track.source.label,
                formatDuration(track.durationSec).takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (downloaded) {
            Icon(
                Icons.Filled.DownloadDone, "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = { actions.toggleLike(track, !liked) }) {
            Icon(
                if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (liked) "Unlike" else "Like",
                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
                    onClick = { menu = false; actions.playNext(track) },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    onClick = { menu = false; actions.addToQueue(track) },
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    onClick = { menu = false; actions.addToPlaylist(track) },
                )
                if (!downloaded) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = { menu = false; actions.download(listOf(track)) },
                    )
                }
                if (track.source == app.mixtape.core.model.SourceType.YOUTUBE) {
                    DropdownMenuItem(
                        text = { Text("Watch video") },
                        leadingIcon = { Icon(Icons.Filled.OndemandVideo, null) },
                        onClick = { menu = false; actions.watchVideo(track) },
                    )
                    DropdownMenuItem(
                        text = { Text("Download video") },
                        leadingIcon = { Icon(Icons.Filled.VideoFile, null) },
                        onClick = { menu = false; actions.downloadVideo(track) },
                    )
                }
                if (track.source == app.mixtape.core.model.SourceType.SPOTIFY) {
                    DropdownMenuItem(
                        text = { Text("Fix YouTube match") },
                        onClick = { menu = false; actions.fixMatch(track) },
                    )
                }
                extraMenu?.invoke { menu = false }
            }
        }
    }
}

@Composable
fun TrackList(
    tracks: List<Track>,
    likedKeys: Set<String>,
    downloadedKeys: Set<String>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    extraMenu: (@Composable (Track, dismiss: () -> Unit) -> Unit)? = null,
    /** Overrides what tapping a row does (default: play the list from that row). */
    onItemClick: ((List<Track>, Int) -> Unit)? = null,
) {
    val actions = LocalTrackActions.current
    LazyColumn(modifier.fillMaxSize()) {
        if (header != null) item { header() }
        items(tracks.size, key = { i -> tracks[i].key + "#" + i }) { i ->
            val t = tracks[i]
            val rowMenu: (@Composable (() -> Unit) -> Unit)? =
                if (extraMenu != null) { { dismiss -> extraMenu(t, dismiss) } } else null
            TrackRow(
                track = t,
                liked = t.key in likedKeys,
                downloaded = t.key in downloadedKeys,
                onClick = { if (onItemClick != null) onItemClick(tracks, i) else actions.play(tracks, i) },
                extraMenu = rowMenu,
            )
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PlaylistPickerDialog(
    playlists: List<PlaylistWithCount>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(bottom = 8.dp)) {
                    items(playlists, key = { it.playlist.id }) { p ->
                        Text(
                            "${p.playlist.name}  (${p.trackCount})",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(p.playlist.id) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { onCreate(newName.trim()) }) {
                Text("Create & add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
