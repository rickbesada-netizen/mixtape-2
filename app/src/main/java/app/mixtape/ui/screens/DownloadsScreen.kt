package app.mixtape.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.data.db.DownloadState
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.EmptyState
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.SectionTitle

@Composable
fun DownloadsScreen() {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val flow = remember { container.downloads.all() }
    val downloads by flow.collectAsStateWithLifecycle(emptyList())

    val done = downloads.filter { it.download.state == DownloadState.DONE && it.track != null }
    val active = downloads.filter { it.download.state != DownloadState.DONE }
    val doneTracks = done.mapNotNull { it.track?.toTrack() }
    val totalBytes = done.sumOf { it.download.bytesTotal }

    if (downloads.isEmpty()) {
        EmptyState("Downloaded songs play offline and stay inside the app. Use the menu on any song to download it.")
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Downloads", Modifier.weight(1f))
                Text(
                    "%.1f MB".format(totalBytes / 1_048_576.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (active.isNotEmpty()) {
            item { Text("In progress", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(active, key = { "a-" + it.download.trackKey }) { d ->
                val t = d.track
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t?.artworkUrl, 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t?.title ?: d.download.trackKey, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (d.download.state) {
                            DownloadState.FAILED -> Text(
                                d.download.error ?: "Failed",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                            DownloadState.QUEUED -> Text(
                                if (d.download.error != null) "Retrying: ${d.download.error}" else "Waiting",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            else -> LinearProgressIndicator(
                                progress = { d.download.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                    if (d.download.state == DownloadState.FAILED) {
                        IconButton(onClick = { container.downloads.retry(d.download.trackKey) }) {
                            Icon(Icons.Filled.Refresh, "Retry")
                        }
                    }
                    IconButton(onClick = { container.downloads.cancel(d.download.trackKey) }) {
                        Icon(Icons.Filled.Close, "Cancel")
                    }
                }
            }
        }
        if (doneTracks.isNotEmpty()) {
            item {
                Row(Modifier.padding(16.dp)) {
                    Button(onClick = { actions.play(doneTracks, 0) }) { Text("Play all offline") }
                }
            }
            items(done, key = { "d-" + it.download.trackKey }) { d ->
                val t = d.track!!.toTrack()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { actions.play(doneTracks, doneTracks.indexOfFirst { it.key == t.key }.coerceAtLeast(0)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t.artworkUrl, 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            t.artist + " · " + "%.1f MB".format(d.download.bytesTotal / 1_048_576.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { container.downloads.delete(t.key) }) {
                        Icon(Icons.Filled.Delete, "Delete download")
                    }
                }
            }
        }
    }
}
