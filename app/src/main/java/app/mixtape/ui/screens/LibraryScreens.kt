package app.mixtape.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.saveable.rememberSaveable
import app.mixtape.data.db.PlaylistWithCount
import app.mixtape.core.model.Track
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.data.db.PlaylistOrigin
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.EmptyState
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.SectionTitle
import app.mixtape.ui.components.TrackList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    openPlaylist: (Long) -> Unit,
    openLiked: () -> Unit,
    openRecent: () -> Unit,
    openSpotify: () -> Unit,
) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlistsFlow = remember { container.library.playlists() }
    val playlists by playlistsFlow.collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(PlaylistSort.NEWEST.name) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort = PlaylistSort.valueOf(sortName)
    val sorted = remember(playlists, sort) { sort.apply(playlists) }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val parsed = withContext(Dispatchers.IO) { CsvImport.read(context, uri) }
                container.library.upsertImportedPlaylist(
                    origin = PlaylistOrigin.CSV,
                    externalId = uri.toString(),
                    name = parsed.name,
                    artworkUrl = parsed.tracks.firstOrNull()?.artworkUrl,
                    list = parsed.tracks,
                ) to parsed.tracks.size
            }.onSuccess { (id, n) ->
                Toast.makeText(context, "Imported $n tracks", Toast.LENGTH_SHORT).show()
                openPlaylist(id)
            }.onFailure {
                Toast.makeText(context, "CSV import failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Library", Modifier.weight(1f))
                IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New playlist") }
            }
        }
        item {
            ListItem(
                headlineContent = { Text("Liked songs") },
                leadingContent = { Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable(onClick = openLiked),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Recently played") },
                leadingContent = { Icon(Icons.Filled.History, null) },
                modifier = Modifier.clickable(onClick = openRecent),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Spotify") },
                supportingContent = { Text("Import your playlists and liked songs") },
                leadingContent = { Icon(Icons.Filled.Sync, null) },
                modifier = Modifier.clickable(onClick = openSpotify),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Import CSV") },
                supportingContent = { Text("Exportify / TuneMyMusic style exports") },
                leadingContent = { Icon(Icons.Filled.UploadFile, null) },
                modifier = Modifier.clickable {
                    csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                },
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Playlists", Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(sort.label)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        PlaylistSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { sortName = option.name; sortMenu = false },
                            )
                        }
                    }
                }
            }
        }
        if (sorted.isEmpty()) {
            item {
                Text(
                    "No playlists yet. Tap + to create one, or import from Spotify.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(sorted, key = { it.playlist.id }) { p ->
            ListItem(
                headlineContent = { Text(p.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val origin = when (p.playlist.origin) {
                        PlaylistOrigin.SPOTIFY -> "Spotify · "
                        PlaylistOrigin.LINK -> "Saved link · "
                        PlaylistOrigin.CSV -> "CSV · "
                        else -> ""
                    }
                    val dl = when {
                        p.trackCount > 0 && p.downloadedCount >= p.trackCount -> " · all downloaded"
                        p.downloadedCount > 0 -> " · ${p.downloadedCount} downloaded"
                        else -> ""
                    }
                    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                        .format(java.util.Date(p.playlist.createdAt))
                    Text("$origin${p.trackCount} tracks$dl · added $date", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Artwork(p.playlist.artworkUrl, 48) },
                trailingContent = if (p.trackCount > 0 && p.downloadedCount >= p.trackCount) {
                    { Icon(Icons.Filled.DownloadDone, "All downloaded", tint = MaterialTheme.colorScheme.primary) }
                } else null,
                modifier = Modifier.clickable { openPlaylist(p.playlist.id) },
            )
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { openPlaylist(container.library.createPlaylist(name.trim())) }
                    creating = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PlaylistScreen(playlistId: Long, onBack: () -> Unit, startDownloadedOnly: Boolean = false) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlistFlow = remember(playlistId) { container.library.playlist(playlistId) }
    val tracksFlow = remember(playlistId) { container.library.playlistTracks(playlistId) }
    val likedFlow = remember { container.library.likedKeys() }
    val doneFlow = remember { container.downloads.doneKeys() }
    val playlist by playlistFlow.collectAsStateWithLifecycle(null)
    val tracks by tracksFlow.collectAsStateWithLifecycle(emptyList())
    val liked by likedFlow.collectAsStateWithLifecycle(emptySet())
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())
    var syncing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var downloadedOnly by rememberSaveable { mutableStateOf(startDownloadedOnly) }
    val shown = remember(tracks, query, downloadedOnly, downloaded) {
        filterTracks(tracks, query).let { l -> if (downloadedOnly) l.filter { it.key in downloaded } else l }
    }
    val downloadedHere = tracks.count { it.key in downloaded }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(playlist?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search in this list")
                }
                val p = playlist
                if (p != null && container.playlistSync.canSync(p)) {
                    IconButton(enabled = !syncing, onClick = {
                        syncing = true
                        scope.launch {
                            // If you've downloaded songs from this playlist, new songs are downloaded too.
                            runCatching { container.playlistSync.syncPlaylist(p, downloadNew = downloadedHere > 0) }
                                .onSuccess { r ->
                                    val msg = when {
                                        r == null -> "Couldn't load the playlist right now"
                                        r.added.isEmpty() -> "Up to date"
                                        downloadedHere > 0 -> "${r.added.size} new songs added and downloading"
                                        else -> "${r.added.size} new songs added"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                            syncing = false
                        }
                    }) { Icon(Icons.Filled.Sync, "Check for new songs") }
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete playlist") }
            },
        )
        if (searching) {
            TrackSearchField(query, onChange = { query = it })
        }
        if (downloadedHere > 0) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !downloadedOnly, onClick = { downloadedOnly = false },
                    label = { Text("All (${tracks.size})") },
                )
                FilterChip(
                    selected = downloadedOnly, onClick = { downloadedOnly = true },
                    label = { Text("Downloaded ($downloadedHere)") },
                )
            }
        }
        if (tracks.isEmpty()) {
            EmptyState("This playlist is empty.")
        } else {
            TrackList(
                shown, liked, downloaded,
                header = {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { actions.play(shown, 0) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play")
                        }
                        FilledTonalButton(onClick = {
                            container.player.playTracks(tracks, (tracks.indices).random(), shuffle = true)
                        }) {
                            Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                        }
                        FilledTonalButton(onClick = { actions.download(tracks.filter { it.key !in downloaded }) }) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("All")
                        }
                        FilledTonalButton(onClick = { actions.addAllToPlaylist(shown, excludePlaylistId = playlistId) }) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (shown.size == tracks.size) "Add all to…" else "Add these ${shown.size} to…")
                        }
                        FilledTonalButton(onClick = { actions.makeClean(playlist?.name ?: "Playlist", tracks) }) {
                            Icon(Icons.Filled.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Clean version")
                        }
                    }
                },
                extraMenu = { track, dismiss ->
                    DropdownMenuItem(
                        text = { Text("Remove from playlist") },
                        onClick = {
                            dismiss()
                            scope.launch { container.library.removeFromPlaylist(playlistId, track.key) }
                        },
                    )
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("Downloaded songs stay in Downloads.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.library.deletePlaylist(playlistId)
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SmartListScreen(kind: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val tracksFlow = remember(kind) {
        if (kind == "recent") container.library.recent() else container.library.likedTracks()
    }
    val likedFlow = remember { container.library.likedKeys() }
    val doneFlow = remember { container.downloads.doneKeys() }
    val tracks by tracksFlow.collectAsStateWithLifecycle(emptyList())
    val liked by likedFlow.collectAsStateWithLifecycle(emptySet())
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val shown = remember(tracks, query) { filterTracks(tracks, query) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(if (kind == "recent") "Recently played" else "Liked songs") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search in this list")
                }
                if (kind == "recent") {
                    IconButton(onClick = { scope.launch { container.library.clearHistory() } }) {
                        Icon(Icons.Filled.Delete, "Clear history")
                    }
                }
            },
        )
        if (searching) {
            TrackSearchField(query, onChange = { query = it })
        }
        if (tracks.isEmpty()) {
            EmptyState(if (kind == "recent") "Nothing played yet." else "Tap the heart on any song to save it here.")
        } else {
            TrackList(
                shown, liked, downloaded,
                header = {
                    Row(Modifier.padding(16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.play(tracks, 0) }) { Text("Play all") }
                        FilledTonalButton(onClick = { actions.download(tracks.filter { it.key !in downloaded }) }) {
                            Text("Download all")
                        }
                        FilledTonalButton(onClick = { actions.addAllToPlaylist(shown) }) { Text("Add to playlist") }
                    }
                },
            )
        }
    }
}

const val SPOTIFY_LIKED_ID = "liked-songs"

enum class PlaylistSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    RECENTLY_CHANGED("Recently changed"),
    NAME("Name A-Z"),
    MOST_SONGS("Most songs"),
    MOST_DOWNLOADED("Most downloaded");

    fun apply(list: List<PlaylistWithCount>): List<PlaylistWithCount> = when (this) {
        NEWEST -> list.sortedByDescending { it.playlist.createdAt }
        OLDEST -> list.sortedBy { it.playlist.createdAt }
        RECENTLY_CHANGED -> list.sortedByDescending { it.playlist.updatedAt }
        NAME -> list.sortedBy { it.playlist.name.lowercase() }
        MOST_SONGS -> list.sortedByDescending { it.trackCount }
        MOST_DOWNLOADED -> list.sortedWith(compareByDescending<PlaylistWithCount> { it.downloadedCount }.thenByDescending { it.trackCount })
    }
}

/** Matches title, artist or album, ignoring case; every word must match somewhere. */
fun filterTracks(tracks: List<Track>, query: String): List<Track> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return tracks
    return tracks.filter { t ->
        val hay = (t.title + " " + t.artist + " " + (t.album ?: "")).lowercase()
        words.all { hay.contains(it) }
    }
}

@Composable
fun TrackSearchField(query: String, onChange: (String) -> Unit, placeholder: String = "Search songs in this list") {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear") }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
