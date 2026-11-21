package app.mixtape.ui.screens

import kotlinx.coroutines.async
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.width
import app.mixtape.ui.components.TrackRow
import app.mixtape.ui.components.SmartSearch
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
    val channels: List<app.mixtape.source.Channel> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false,
    /** "All": one search across every platform, shown in sections (the default). */
    val all: Boolean = true,
    val top: AllResults? = null,
)

/** Results of an "All" search, one section per kind. */
data class AllResults(
    val songs: List<Track>,
    val videos: List<Track>,
    val soundcloud: List<Track>,
    val albums: List<RemoteCollection>,
    val playlists: List<RemoteCollection>,
    val channels: List<app.mixtape.source.Channel>,
) {
    val isEmpty get() = songs.isEmpty() && videos.isEmpty() && soundcloud.isEmpty() && albums.isEmpty() && playlists.isEmpty() && channels.isEmpty()
}

class SearchViewModel(private val container: AppContainer) : ViewModel() {
    /** Every platform at once: YouTube Music, YouTube, SoundCloud, Deezer, Spotify (if connected). */
    private suspend fun searchEverywhere(q: String): AllResults = kotlinx.coroutines.coroutineScope {
        val src = container.sources
        suspend fun <T> safe(block: suspend () -> List<T>): List<T> = runCatching { block() }.getOrDefault(emptyList())
        val songs = async { safe { src.search(q, SearchFilter.YT_MUSIC_SONGS) } }
        val videos = async { safe { src.search(q, SearchFilter.YT_VIDEOS) } }
        val sc = async { safe { src.search(q, SearchFilter.SOUNDCLOUD) } }
        val albums = async { safe { src.searchCollections(q, SearchFilter.YT_MUSIC_ALBUMS) } }
        val ytLists = async { safe { src.searchCollections(q, SearchFilter.YT_PLAYLISTS) } }
        val dzLists = async { safe { container.deezer.searchPlaylists(q, 10) } }
        val spLists = async { if (container.spotifyAuth.loggedIn.value) safe { container.spotify.searchPlaylists(q, 1) } else emptyList() }
        val channels = async { safe { container.subscriptions.searchChannels(q) } }
        AllResults(
            songs = songs.await().take(8),
            videos = videos.await().take(6),
            soundcloud = sc.await().take(6),
            albums = albums.await().take(12),
            playlists = (ytLists.await().take(8) + spLists.await().take(6) + dzLists.await().take(8)).distinctBy { it.url },
            channels = channels.await().take(10),
        )
    }

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun setAll() {
        _state.update { it.copy(all = true) }
        val q = _state.value.query
        if (q.isNotBlank() && !LinkResolver.looksLikeLink(q)) search()
    }

    fun setFilter(f: SearchFilter) {
        _state.update { it.copy(filter = f, all = false) }
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
            if (_state.value.all) {
                val top = searchEverywhere(q)
                _state.update {
                    it.copy(
                        top = top, loading = false, searched = true,
                        error = if (top.isEmpty) "Nothing found, or no internet connection" else null,
                    )
                }
                return@launch
            }
            if (filter == SearchFilter.YT_CHANNELS) {
                runCatching { container.subscriptions.searchChannels(q) }
                    .onSuccess { ch -> _state.update { it.copy(channels = ch, results = emptyList(), collections = emptyList(), loading = false, searched = true) } }
                    .onFailure { e -> _state.update { it.copy(loading = false, searched = true, error = e.message ?: "Search failed") } }
                return@launch
            }
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
fun SearchScreen(openCollection: (String) -> Unit, openChannel: (String) -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val vm = containerViewModel { SearchViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val likedFlow = remember { container.library.likedKeys() }
    val doneFlow = remember { container.downloads.doneKeys() }
    val liked by likedFlow.collectAsStateWithLifecycle(emptySet())
    val downloaded by doneFlow.collectAsStateWithLifecycle(emptySet())
    val isLink = LinkResolver.looksLikeLink(state.query)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SmartSearch(
        box = "music",
        query = state.query,
        onQueryChange = vm::setQuery,
        onSubmit = { q -> if (LinkResolver.looksLikeLink(q)) actions.openLink(q) else vm.search() },
        placeholder = "Songs, artists, videos, playlists, or paste a link",
        isLink = isLink,
    ) {
        if (isLink) {
            Column {
                Button(onClick = { actions.openLink(state.query) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Open link") }
                Text(
                    "Works with songs, playlists and albums from YouTube, YouTube Music, SoundCloud, Spotify and Deezer. " +
                        "You can also use Share > Mixtape from those apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = state.all, onClick = { vm.setAll() }, label = { Text("All") })
                SearchFilter.entries.forEach { f ->
                    FilterChip(selected = !state.all && state.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
                }
            }

            val top = state.top
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> EmptyState("Couldn't search: ${state.error}")
                !state.searched -> EmptyState("Search YouTube Music, YouTube, SoundCloud, Spotify and Deezer at once. Tap the mic to search by voice.")
                state.all && top != null -> AllResultsList(top, liked, downloaded, openCollection, openChannel, onSeeAll = { vm.setFilter(it) })
                state.filter == SearchFilter.YT_CHANNELS && state.channels.isEmpty() -> EmptyState("No channels found")
                state.filter == SearchFilter.YT_CHANNELS -> {
                    val subsFlow = remember { container.subscriptions.subscribedIds() }
                    val subbed by subsFlow.collectAsStateWithLifecycle(emptySet())
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.channels, key = { it.id }) { c ->
                            ChannelResultRow(
                                c, subscribed = c.id in subbed,
                                onOpen = { openChannel(c.id) },
                                onToggle = {
                                    scope.launch {
                                        if (c.id in subbed) container.subscriptions.unsubscribe(c.id)
                                        else container.subscriptions.subscribe(c)
                                    }
                                },
                            )
                        }
                    }
                }
                state.filter.collections && state.collections.isEmpty() -> EmptyState("No playlists found")
                state.filter.collections -> CollectionList(state.collections, openCollection)
                state.results.isEmpty() -> EmptyState("No results")
                // Videos open in the video player, like YouTube; songs play as audio.
                state.filter == SearchFilter.YT_VIDEOS -> TrackList(
                    state.results, liked, downloaded,
                    onItemClick = { list, i -> actions.watchVideo(list[i]) },
                )
                // Like Spotify: tapping a song plays it, then similar songs (autoplay radio).
                else -> TrackList(
                    state.results, liked, downloaded,
                    onItemClick = { list, i -> actions.play(listOf(list[i]), 0) },
                )
            }
        }
    }
}

@Composable
private fun ResultHeader(title: String, filter: SearchFilter?, onSeeAll: (SearchFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (filter != null) TextButton(onClick = { onSeeAll(filter) }) { Text("See all") }
    }
}

/** "All" results in sections, like Spotify's search. */
@Composable
private fun AllResultsList(
    top: AllResults,
    liked: Set<String>,
    downloaded: Set<String>,
    openCollection: (String) -> Unit,
    openChannel: (String) -> Unit,
    onSeeAll: (SearchFilter) -> Unit,
) {
    val actions = LocalTrackActions.current
    LazyColumn(Modifier.fillMaxSize()) {
        if (top.songs.isNotEmpty()) {
            item { ResultHeader("Songs", SearchFilter.YT_MUSIC_SONGS, onSeeAll) }
            items(top.songs, key = { "s" + it.key }) { t ->
                TrackRow(t, t.key in liked, t.key in downloaded, onClick = { actions.play(listOf(t), 0) })
            }
        }
        if (top.channels.isNotEmpty()) {
            item {
                ResultHeader("Artists & channels", SearchFilter.YT_CHANNELS, onSeeAll)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    top.channels.forEach { c ->
                        Column(
                            Modifier.width(88.dp).clickable { openChannel(c.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ChannelAvatar(c.avatarUrl, 72)
                            Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
        if (top.videos.isNotEmpty()) {
            item { ResultHeader("Videos", SearchFilter.YT_VIDEOS, onSeeAll) }
            items(top.videos, key = { "v" + it.key }) { t ->
                TrackRow(t, t.key in liked, t.key in downloaded, onClick = { actions.watchVideo(t) })
            }
        }
        if (top.albums.isNotEmpty()) {
            item { ResultHeader("Albums", SearchFilter.YT_MUSIC_ALBUMS, onSeeAll); CollectionRow(top.albums) { openCollection(it.url) } }
        }
        if (top.playlists.isNotEmpty()) {
            item { ResultHeader("Playlists", SearchFilter.YT_PLAYLISTS, onSeeAll); CollectionRow(top.playlists) { openCollection(it.url) } }
        }
        if (top.soundcloud.isNotEmpty()) {
            item { ResultHeader("On SoundCloud", SearchFilter.SOUNDCLOUD, onSeeAll) }
            items(top.soundcloud, key = { "c" + it.key }) { t ->
                TrackRow(t, t.key in liked, t.key in downloaded, onClick = { actions.play(listOf(t), 0) })
            }
        }
        item { Box(Modifier.padding(24.dp)) }
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
