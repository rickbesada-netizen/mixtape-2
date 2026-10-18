package app.mixtape.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mixtape.AppContainer
import app.mixtape.core.model.CollectionDetail
import app.mixtape.data.db.PlaylistOrigin
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.EmptyState
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.TrackList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CollectionUiState(
    val loading: Boolean = true,
    val detail: CollectionDetail? = null,
    val error: String? = null,
)

class CollectionViewModel(private val c: AppContainer, private val url: String) : ViewModel() {
    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = CollectionUiState(loading = true, detail = c.links.cached(url))
        viewModelScope.launch {
            runCatching { c.links.loadCollection(url) }
                .onSuccess { _state.value = CollectionUiState(loading = false, detail = it) }
                .onFailure { _state.value = CollectionUiState(loading = false, error = it.message ?: "Couldn't load") }
        }
    }
}

/** A playlist or album from a link or search result, playable before (or without) saving it. */
@Composable
fun CollectionScreen(url: String, onBack: () -> Unit, openSavedPlaylist: (Long) -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val vm = containerViewModel(key = "collection:$url") { CollectionViewModel(it, url) }
    val state by vm.state.collectAsStateWithLifecycle()
    val likedFlow = remember { container.library.likedKeys() }
    val doneFlow = remember { container.downloads.doneKeys() }
    val liked by likedFlow.collectAsStateWithLifecycle(emptySet())
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())
    var savedId by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(state.detail?.info?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        val detail = state.detail
        when {
            detail == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Loading tracks...", modifier = Modifier.padding(top = 12.dp))
                }
            }
            detail == null -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text(state.error ?: "Couldn't load this playlist", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.load() }) { Text("Try again") }
            }
            detail.tracks.isEmpty() -> EmptyState("No playable tracks in this playlist.")
            else -> TrackList(
                detail.tracks, liked, downloaded,
                header = {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(detail.info.artworkUrl, 96)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(detail.info.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(detail.info.subtitle, "${detail.tracks.size} tracks").filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (state.loading) Text("Refreshing...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { actions.play(detail.tracks, 0) }) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play")
                            }
                            FilledTonalButton(onClick = {
                                scope.launch { container.library.saveTracks(detail.tracks) }
                                container.player.playTracks(detail.tracks, detail.tracks.indices.random(), shuffle = true)
                            }) {
                                Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                            }
                            FilledTonalButton(onClick = {
                                val id = savedId
                                if (id != null) {
                                    openSavedPlaylist(id)
                                } else scope.launch {
                                    savedId = container.library.upsertImportedPlaylist(
                                        origin = PlaylistOrigin.LINK,
                                        externalId = detail.info.url,
                                        name = detail.info.title,
                                        artworkUrl = detail.info.artworkUrl,
                                        list = detail.tracks,
                                    )
                                }
                            }) {
                                Icon(if (savedId != null) Icons.Filled.LibraryAddCheck else Icons.Filled.LibraryAdd, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (savedId != null) "Saved" else "Save")
                            }
                            FilledTonalButton(onClick = {
                                // Save the playlist first so Downloads can show it as one playlist.
                                scope.launch {
                                    val id = savedId ?: container.library.upsertImportedPlaylist(
                                        origin = PlaylistOrigin.LINK,
                                        externalId = detail.info.url,
                                        name = detail.info.title,
                                        artworkUrl = detail.info.artworkUrl,
                                        list = detail.tracks,
                                    )
                                    savedId = id
                                    actions.download(detail.tracks.filter { it.key !in downloaded })
                                }
                            }) {
                                Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Download")
                            }
                        }
                    }
                },
            )
        }
    }
}
