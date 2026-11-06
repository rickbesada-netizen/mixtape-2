package app.mixtape.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import app.mixtape.core.model.RemoteCollection
import app.mixtape.source.LinkResolver
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.LocalTrackActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mixtape.AppContainer
import app.mixtape.core.model.SearchFilter
import app.mixtape.core.model.Track
import app.mixtape.ui.components.EmptyState
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.TrackList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Creates a ViewModel that receives the app container. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline build: (AppContainer) -> VM,
): VM {
    val container = LocalContainer.current
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = build(container) as T
        },
    )
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.YT_MUSIC_SONGS,
    val loading: Boolean = false,
    val results: List<Track> = emptyList(),
    val collections: List<RemoteCollection> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false,
)

class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun setFilter(f: SearchFilter) {
        _state.update { it.copy(filter = f) }
        val q = _state.value.query
        if (q.isNotBlank() && !LinkResolver.looksLikeLink(q)) search()
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        val filter = _state.value.filter
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                if (filter.collections) emptyList<Track>() to container.sources.searchCollections(q, filter)
                else container.sources.search(q, filter) to emptyList<RemoteCollection>()
            }.onSuccess { (tracks, collections) ->
                _state.update {
                    it.copy(results = tracks, collections = collections, loading = false, searched = true)
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, searched = true, error = e.message ?: "Search failed")
                }
            }
        }
    }
}

@Composable
fun SearchScreen(openCollection: (String) -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val vm = containerViewModel { SearchViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val likedFlow = remember { container.library.likedKeys() }
    val doneFlow = remember { container.downloads.doneKeys() }
    val liked by likedFlow.collectAsStateWithLifecycle(emptySet())
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())
    val focus = LocalFocusManager.current
    val isLink = LinkResolver.looksLikeLink(state.query)

    fun submit() {
        focus.clearFocus()
        if (isLink) actions.openLink(state.query) else vm.search()
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Search, or paste a YouTube / Spotify / SoundCloud link") },
            leadingIcon = { Icon(if (isLink) Icons.Filled.Link else Icons.Filled.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, "Clear") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = if (isLink) ImeAction.Go else ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }, onGo = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (isLink) {
            Button(
                onClick = { submit() },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("Open link") }
            Text(
                "Works with songs, playlists and albums from YouTube, YouTube Music, SoundCloud and Spotify. " +
                    "You can also use Share > Mixtape from those apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchFilter.entries.forEach { f ->
                FilterChip(
                    selected = state.filter == f,
                    onClick = { vm.setFilter(f) },
                    label = { Text(f.label) },
                )
            }
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> EmptyState("Couldn't search: ${state.error}")
            !state.searched -> EmptyState("Search YouTube Music, YouTube and SoundCloud from one place, or paste a link.")
            state.filter.collections && state.collections.isEmpty() -> EmptyState("No playlists found")
            state.filter.collections -> CollectionList(state.collections, openCollection)
            state.results.isEmpty() -> EmptyState("No results")
            // Videos open in the video player, like YouTube; songs play as audio.
            state.filter == SearchFilter.YT_VIDEOS -> TrackList(
                state.results, liked, downloaded,
                onItemClick = { list, i -> actions.watchVideo(list[i]) },
            )
            else -> TrackList(state.results, liked, downloaded)
        }
        }
    }
}

@Composable
fun CollectionList(items: List<RemoteCollection>, open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.url }) { c ->
            ListItem(
                headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(c.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Artwork(c.artworkUrl, 56) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                modifier = Modifier.clickable { open(c.url) },
            )
        }
    }
}
