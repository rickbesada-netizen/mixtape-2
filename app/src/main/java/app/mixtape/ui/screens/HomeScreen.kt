package app.mixtape.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mixtape.AppContainer
import app.mixtape.core.model.RemoteCollection
import app.mixtape.core.model.Track
import app.mixtape.core.model.display
import app.mixtape.core.model.formatDuration
import app.mixtape.core.net.YouTubeLimits
import app.mixtape.data.db.PlaylistWithCount
import app.mixtape.source.FeedKind
import app.mixtape.source.FeedSection
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = false,
    val sections: List<FeedSection> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(sections = c.home.peek()))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { load(force = false) }

    fun load(force: Boolean) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { c.home.load(force) }
                .onSuccess { s ->
                    _state.value = HomeUiState(
                        sections = s.ifEmpty { _state.value.sections },
                        error = if (s.isEmpty() && YouTubeLimits.isPaused()) YouTubeLimits.FRIENDLY_MESSAGE
                        else if (s.isEmpty()) "Couldn't load the feed. Check your connection and tap refresh." else null,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }
}

@Composable
fun HomeScreen(openPlaylist: (Long) -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val vm = containerViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val recentFlow = remember { container.library.recent() }
    val playlistsFlow = remember { container.library.playlists() }
    val recent by recentFlow.collectAsStateWithLifecycle(emptyList())
    val playlists by playlistsFlow.collectAsStateWithLifecycle(emptyList())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Home", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = { vm.load(force = true) }, enabled = !state.loading) {
                    Icon(Icons.Filled.Refresh, "Refresh")
                }
            }
        }

        if (recent.isNotEmpty()) {
            item {
                SectionHeader("Recently played") {
                    FilledTonalButton(onClick = { actions.play(recent.shuffled(), 0) }) {
                        Icon(Icons.Filled.Shuffle, null, Modifier.size(16.dp)); Text(" Shuffle")
                    }
                }
                MusicRow(recent.take(20)) { i -> actions.play(recent, i) }
            }
        }
        if (playlists.isNotEmpty()) {
            item {
                SectionHeader("Your playlists")
                PlaylistRow(playlists.sortedByDescending { it.playlist.updatedAt }.take(15), openPlaylist)
            }
        }

        state.error?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(msg, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.sections.isEmpty() && state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }

        items(state.sections, key = { it.id }) { section ->
            SectionHeader(section.title)
            when (section.kind) {
                FeedKind.MUSIC -> MusicRow(section.tracks) { i -> actions.play(section.tracks, i) }
                FeedKind.VIDEO -> VideoRow(section.tracks) { i -> actions.watchVideo(section.tracks[i]) }
                FeedKind.COLLECTIONS -> CollectionRow(section.collections) { actions.openCollection(it.url) }
            }
        }

        if (!state.loading && state.sections.isEmpty() && state.error == null) {
            item {
                Text(
                    "Play a few songs and your picks will show up here.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun MusicRow(tracks: List<Track>, onTap: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(tracks, key = { i, t -> t.key + "#" + i }) { i, t ->
            val shown = remember(t) { t.display() }
            Column(Modifier.width(140.dp).clickable { onTap(i) }) {
                Artwork(t.artworkUrl, 140)
                Text(shown.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                Text(shown.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VideoRow(tracks: List<Track>, onTap: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(tracks, key = { i, t -> t.key + "#" + i }) { i, t ->
            Column(Modifier.width(240.dp).clickable { onTap(i) }) {
                Box(
                    Modifier.width(240.dp).height(135.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (t.artworkUrl != null) {
                        AsyncImage(model = t.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    if (t.durationSec > 0) {
                        Text(
                            formatDuration(t.durationSec),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Icon(Icons.Filled.PlayCircle, null, tint = Color(0xDDFFFFFF), modifier = Modifier.align(Alignment.Center).size(40.dp))
                }
                Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CollectionRow(items: List<RemoteCollection>, onTap: (RemoteCollection) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.url }) { c ->
            Column(Modifier.width(140.dp).clickable { onTap(c) }) {
                Artwork(c.artworkUrl, 140)
                Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                Text(c.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlaylistRow(items: List<PlaylistWithCount>, open: (Long) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.playlist.id }) { p ->
            Column(Modifier.width(120.dp).clickable { open(p.playlist.id) }) {
                if (p.playlist.artworkUrl != null) Artwork(p.playlist.artworkUrl, 120)
                else Box(
                    Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.MusicNote, null) }
                Text(p.playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                Text("${p.trackCount} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
