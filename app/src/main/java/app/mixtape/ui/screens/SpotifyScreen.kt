package app.mixtape.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mixtape.AppContainer
import app.mixtape.data.db.PlaylistOrigin
import app.mixtape.spotify.SPOTIFY_REDIRECT_URI
import app.mixtape.spotify.SpotifyApi
import app.mixtape.spotify.SpotifyPlaylist
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.LocalContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpotifyUiState(
    val loading: Boolean = false,
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val importing: String? = null,
    val message: String? = null,
    val importedId: Long? = null,
)

class SpotifyViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SpotifyUiState())
    val state: StateFlow<SpotifyUiState> = _state.asStateFlow()

    fun loadPlaylists() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            runCatching { c.spotify.myPlaylists() }
                .onSuccess { list -> _state.update { it.copy(loading = false, playlists = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, message = e.message) } }
        }
    }

    fun importPlaylist(p: SpotifyPlaylist) = runImport(p.id, p.name) {
        val tracks = c.spotify.playlistTracks(p.id)
        c.library.upsertImportedPlaylist(PlaylistOrigin.SPOTIFY, p.id, p.name, p.imageUrl, tracks) to tracks.size
    }

    fun importLiked() = runImport(SPOTIFY_LIKED_ID, "Liked Songs") {
        val tracks = c.spotify.savedTracks()
        c.library.upsertImportedPlaylist(
            PlaylistOrigin.SPOTIFY, SPOTIFY_LIKED_ID, "Spotify Liked Songs",
            tracks.firstOrNull()?.artworkUrl, tracks,
        ) to tracks.size
    }

    fun importByLink(link: String) {
        val id = SpotifyApi.parsePlaylistId(link)
        if (id == null) {
            _state.update { it.copy(message = "That doesn't look like a Spotify playlist link") }
            return
        }
        runImport(id, "playlist") {
            val info = c.spotify.playlistInfo(id)
            val tracks = c.spotify.playlistTracks(id)
            c.library.upsertImportedPlaylist(PlaylistOrigin.SPOTIFY, id, info.name, info.imageUrl, tracks) to tracks.size
        }
    }

    private fun runImport(id: String, label: String, block: suspend () -> Pair<Long, Int>) {
        viewModelScope.launch {
            _state.update { it.copy(importing = id, message = null) }
            runCatching { block() }
                .onSuccess { (playlistId, n) ->
                    _state.update { it.copy(importing = null, message = "Imported $n tracks from $label", importedId = playlistId) }
                }
                .onFailure { e -> _state.update { it.copy(importing = null, message = e.message) } }
        }
    }

    fun consumeImported() = _state.update { it.copy(importedId = null) }
}

@Composable
fun SpotifyScreen(openPlaylist: (Long) -> Unit, onBack: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val vm = containerViewModel { SpotifyViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val loggedIn by container.spotifyAuth.loggedIn.collectAsStateWithLifecycle()
    val savedClientId by container.spotifyAuth.clientId.collectAsStateWithLifecycle()
    var clientId by remember(savedClientId) { mutableStateOf(savedClientId) }
    var link by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(loggedIn) { if (loggedIn) vm.loadPlaylists() }
    LaunchedEffect(state.importedId) {
        state.importedId?.let { vm.consumeImported(); openPlaylist(it) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("Spotify") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (loggedIn) OutlinedButton(onClick = { container.spotifyAuth.logout() }) { Text("Log out") }
            },
        )

        if (!loggedIn) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Spotify is used only for your playlists and liked songs. Audio is matched and played from YouTube.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("One-time setup:", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Go to developer.spotify.com/dashboard and create an app (choose Web API).\n" +
                        "2. Add this Redirect URI exactly:",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(SPOTIFY_REDIRECT_URI, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "3. Copy the app's Client ID below. Development Mode apps need the app owner to have " +
                        "Spotify Premium, and allow up to 5 users (add them under User Management).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = clientId.isNotBlank(),
                    onClick = {
                        container.spotifyAuth.setClientId(clientId)
                        runCatching { container.spotifyAuth.buildAuthorizeUri() }
                            .onSuccess { context.startActivity(Intent(Intent.ACTION_VIEW, it)) }
                            .onFailure { loginError = it.message }
                    },
                ) { Text("Log in with Spotify") }
                loginError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "No Spotify account access? Import a CSV export from the Library tab instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            label = { Text("Paste a playlist link") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(enabled = link.isNotBlank() && state.importing == null, onClick = { vm.importByLink(link) }) {
                            Text("Import")
                        }
                    }
                    Button(enabled = state.importing == null, onClick = { vm.importLiked() }) {
                        Icon(Icons.Filled.Favorite, null, Modifier.size(18.dp))
                        Text("  Import Liked Songs")
                    }
                    state.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.importing != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  Importing...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("Your playlists", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (state.loading) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            }
            items(state.playlists, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${p.owner} · ${p.trackCount} tracks") },
                    leadingContent = { Artwork(p.imageUrl, 48) },
                    trailingContent = {
                        if (state.importing == p.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else OutlinedButton(enabled = state.importing == null, onClick = { vm.importPlaylist(p) }) { Text("Import") }
                    },
                )
            }
        }
    }
}
