package app.mixtape.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.AppContainer
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.data.db.DownloadState
import app.mixtape.data.db.DownloadWithTrack
import app.mixtape.data.db.PlaylistWithCount
import app.mixtape.download.VideoExport
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.EmptyState
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.SectionTitle
import app.mixtape.ui.components.TrackRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DownloadsView(val label: String) { PLAYLISTS("Playlists"), SONGS("All songs"), VIDEOS("Videos") }

/**
 * Downloads, organised the way you downloaded them: each playlist you downloaded is one entry
 * (tap to open it), songs downloaded on their own are listed under it, videos have their own tab.
 */
@Composable
fun DownloadsScreen(openVideo: (String) -> Unit, openPlaylist: (Long) -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val downloadsFlow = remember { container.downloads.all() }
    val playlistsFlow = remember { container.library.playlists() }
    val inPlaylistsFlow = remember { container.library.keysInPlaylists() }
    val likedFlow = remember { container.library.likedKeys() }
    val downloads by downloadsFlow.collectAsStateWithLifecycle(emptyList())
    val playlists by playlistsFlow.collectAsStateWithLifecycle(emptyList())
    val inPlaylists by inPlaylistsFlow.collectAsStateWithLifecycle(emptySet())
    val liked by likedFlow.collectAsStateWithLifecycle(emptySet())
    val restorable by container.cloud.restorable.collectAsStateWithLifecycle()

    val allDone = downloads.filter { it.download.state == DownloadState.DONE && it.track != null }
    val videos = allDone.filter { it.download.trackKey.startsWith("ytv:") }
    val songs = allDone.filter { !it.download.trackKey.startsWith("ytv:") }
    val active = downloads.filter { it.download.state != DownloadState.DONE }
    val doneKeys = remember(allDone) { allDone.map { it.download.trackKey }.toSet() }
    val downloadedPlaylists = playlists
        .filter { it.downloadedCount > 0 }
        .sortedByDescending { it.lastDownloadAt ?: 0L }
    val looseSongs = songs.filter { it.download.trackKey !in inPlaylists }
    val totalBytes = allDone.sumOf { it.download.bytesTotal }

    var view by rememberSaveable { mutableStateOf(DownloadsView.PLAYLISTS.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchAll by rememberSaveable { mutableStateOf(false) }
    var showActive by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<Track, List<String>>>>(emptyList()) }

    LaunchedEffect(query, searchAll, allDone.size) {
        if (query.isBlank()) {
            results = emptyList()
        } else {
            delay(200)
            results = container.library.searchLibrary(query, downloadsOnly = !searchAll)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Downloads", Modifier.weight(1f))
            Text(
                "${songs.size} songs · %.0f MB".format(totalBytes / 1_048_576.0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TrackSearchField(
            query, onChange = { query = it },
            placeholder = if (searchAll) "Search every song in every playlist" else "Search all your downloads",
        )

        if (query.isNotBlank()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !searchAll, onClick = { searchAll = false }, label = { Text("Downloads") })
                FilterChip(selected = searchAll, onClick = { searchAll = true }, label = { Text("All my playlists") })
            }
            SearchResults(results, query, searchAll, liked, doneKeys, openVideo)
        } else if (downloads.isEmpty() && restorable.isEmpty()) {
            EmptyState(
                "Nothing downloaded yet. Open a playlist and tap Download, or use the menu on any song. " +
                    "Downloads play offline and stay inside the app."
            )
        } else {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadsView.entries.forEach { v ->
                val count = when (v) {
                    DownloadsView.PLAYLISTS -> downloadedPlaylists.size
                    DownloadsView.SONGS -> songs.size
                    DownloadsView.VIDEOS -> videos.size
                }
                FilterChip(selected = view == v.name, onClick = { view = v.name }, label = { Text("${v.label} ($count)") })
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (restorable.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${restorable.size} downloads from your account aren't on this phone")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Button(onClick = {
                                    actions.download(restorable)
                                    container.cloud.clearRestorable()
                                }) { Text("Restore all") }
                                TextButton(onClick = { container.cloud.clearRestorable() }) { Text("Not now") }
                            }
                        }
                    }
                }
            }

            if (active.isNotEmpty()) {
                item {
                    ActiveSummary(active, expanded = showActive, onToggle = { showActive = !showActive })
                }
                if (showActive) {
                    items(active, key = { "a-" + it.download.trackKey }) { d -> ActiveRow(d, container) }
                }
            }

            when (DownloadsView.valueOf(view)) {
                DownloadsView.PLAYLISTS -> {
                    if (downloadedPlaylists.isEmpty() && looseSongs.isEmpty()) {
                        item { EmptyHint("No downloaded playlists yet. Open a playlist and tap Download.") }
                    }
                    items(downloadedPlaylists, key = { "p-" + it.playlist.id }) { p ->
                        DownloadedPlaylistRow(p) { openPlaylist(p.playlist.id) }
                    }
                    if (looseSongs.isNotEmpty()) {
                        item {
                            Text(
                                "Songs not in a playlist (${looseSongs.size})",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        songRows(looseSongs, liked, actions::play, container)
                    }
                }
                DownloadsView.SONGS -> {
                    if (songs.isEmpty()) item { EmptyHint("No songs downloaded yet.") }
                    else {
                        item {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    actions.play(songs.mapNotNull { it.track?.toTrack() }, 0)
                                }) { Text("Play all offline") }
                            }
                        }
                        songRows(songs, liked, actions::play, container)
                    }
                }
                DownloadsView.VIDEOS -> {
                    if (videos.isEmpty()) item { EmptyHint("No videos downloaded. Use \"Download video\" in a song's menu.") }
                    items(videos, key = { "v-" + it.download.trackKey }) { d ->
                        VideoRow(d, openVideo, container, scope, context)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun DownloadedPlaylistRow(p: PlaylistWithCount, onOpen: () -> Unit) {
    val complete = p.trackCount > 0 && p.downloadedCount >= p.trackCount
    ListItem(
        headlineContent = { Text(p.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    if (complete) "All ${p.trackCount} songs downloaded" else "${p.downloadedCount} of ${p.trackCount} songs downloaded",
                    maxLines = 1,
                )
                if (!complete && p.trackCount > 0) {
                    LinearProgressIndicator(
                        progress = { p.downloadedCount.toFloat() / p.trackCount },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
        leadingContent = { Artwork(p.playlist.artworkUrl, 56) },
        trailingContent = {
            if (complete) Icon(Icons.Filled.DownloadDone, "Fully downloaded", tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun ActiveSummary(active: List<DownloadWithTrack>, expanded: Boolean, onToggle: () -> Unit) {
    val running = active.count { it.download.state == DownloadState.RUNNING || it.download.state == DownloadState.QUEUED }
    val failed = active.count { it.download.state == DownloadState.FAILED }
    val avg = if (active.isEmpty()) 0f else active.map { it.download.progress.coerceIn(0f, 1f) }.average().toFloat()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onToggle)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        if (running > 0) append("Downloading $running songs")
                        if (failed > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("$failed failed")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Hide" else "Show")
            }
            if (running > 0) {
                LinearProgressIndicator(progress = { avg }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ActiveRow(d: DownloadWithTrack, container: AppContainer) {
    val t = d.track
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t?.artworkUrl, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t?.title ?: d.download.trackKey, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (d.download.state) {
                DownloadState.FAILED -> Text(
                    d.download.error ?: "Failed", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2,
                )
                DownloadState.QUEUED -> Text(
                    if (d.download.error != null) "Retrying: ${d.download.error}" else "Waiting",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                else -> LinearProgressIndicator(
                    progress = { d.download.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        if (d.download.state == DownloadState.FAILED) {
            IconButton(onClick = { container.downloads.retry(d.download.trackKey) }) { Icon(Icons.Filled.Refresh, "Retry") }
        }
        IconButton(onClick = { container.downloads.cancel(d.download.trackKey) }) { Icon(Icons.Filled.Close, "Cancel") }
    }
}

private fun LazyListScope.songRows(
    list: List<DownloadWithTrack>,
    liked: Set<String>,
    play: (List<Track>, Int) -> Unit,
    container: AppContainer,
) {
    val tracks = list.mapNotNull { it.track?.toTrack() }
    items(list.size, key = { i -> "s-" + list[i].download.trackKey }) { i ->
        val t = list[i].track?.toTrack()
        if (t != null) TrackRow(
            track = t,
            liked = t.key in liked,
            downloaded = true,
            onClick = { play(tracks, tracks.indexOfFirst { it.key == t.key }.coerceAtLeast(0)) },
            extraMenu = { dismiss ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Delete download") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { dismiss(); container.downloads.delete(t.key) },
                )
            },
        )
    }
}

@Composable
private fun VideoRow(
    d: DownloadWithTrack,
    openVideo: (String) -> Unit,
    container: AppContainer,
    scope: CoroutineScope,
    context: android.content.Context,
) {
    val t = d.track ?: return
    val id = d.download.trackKey.removePrefix("ytv:")
    Row(
        Modifier.fillMaxWidth().clickable { openVideo(id) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.artworkUrl, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Video · " + "%.1f MB".format(d.download.bytesTotal / 1_048_576.0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = {
            scope.launch {
                val file = container.downloads.completedFile(d.download.trackKey) ?: return@launch
                runCatching { VideoExport.saveToPhone(context, file, container.crypto.dataKey, t.title) }
                    .onSuccess { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }) { Icon(Icons.Filled.SaveAlt, "Save to phone") }
        IconButton(onClick = { container.downloads.delete(d.download.trackKey) }) {
            Icon(Icons.Filled.Delete, "Delete download")
        }
    }
}

@Composable
private fun SearchResults(
    results: List<Pair<Track, List<String>>>,
    query: String,
    searchAll: Boolean,
    liked: Set<String>,
    doneKeys: Set<String>,
    openVideo: (String) -> Unit,
) {
    val actions = LocalTrackActions.current
    if (results.isEmpty()) {
        EmptyState(
            if (searchAll) "No songs in your playlists match \"$query\""
            else "No downloads match \"$query\". Try \"All my playlists\"."
        )
        return
    }
    val resultTracks = results.map { it.first }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results.size, key = { i -> "r-" + results[i].first.key }) { i ->
            val (t, inLists) = results[i]
            Column {
                TrackRow(
                    track = t,
                    liked = t.key in liked,
                    downloaded = t.key in doneKeys,
                    onClick = {
                        if (t.source == SourceType.YOUTUBE_VIDEO) openVideo(t.sourceId)
                        else actions.play(resultTracks, i)
                    },
                )
                if (inLists.isNotEmpty()) {
                    Text(
                        "In: " + inLists.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 76.dp, end = 16.dp, bottom = 6.dp),
                    )
                }
            }
        }
    }
}
