package app.mixtape.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mixtape.core.model.SearchFilter
import app.mixtape.core.model.Track
import app.mixtape.core.model.formatDuration
import app.mixtape.matcher.ScoredCandidate
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.EmptyState
import app.mixtape.ui.components.LocalContainer
import kotlinx.coroutines.launch

/** Lets the user pick which YouTube upload a Spotify track should play. */
@Composable
fun MatchScreen(spotifyKey: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Track?>(null) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<ScoredCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(spotifyKey) {
        val t = container.library.getTrack(spotifyKey)
        original = t
        currentId = container.matcher.currentMatch(spotifyKey)?.youtubeId
        if (t == null) {
            error = "Track not found"
            loading = false
            return@LaunchedEffect
        }
        query = "${t.artist.split(",").first().trim()} ${t.title}"
        runCatching { container.matcher.candidates(t) }
            .onSuccess { candidates = it }
            .onFailure { error = it.message }
        loading = false
    }

    fun searchManually() {
        val t = original ?: return
        loading = true
        scope.launch {
            runCatching {
                val songs = container.sources.youtube.search(query, SearchFilter.YT_MUSIC_SONGS)
                val videos = container.sources.youtube.search(query, SearchFilter.YT_VIDEOS)
                (songs + videos).distinctBy { it.sourceId }
                    .map { ScoredCandidate(it, container.matcher.score(t, it)) }
            }.onSuccess { candidates = it; error = null }
                .onFailure { error = it.message }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("Choose the right song") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        original?.let { t ->
            Text(
                "Spotify: ${t.title} — ${t.artist} (${formatDuration(t.durationSec)})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search YouTube") },
            trailingIcon = { IconButton(onClick = { searchManually() }) { Icon(Icons.Filled.Search, "Search") } },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null && candidates.isEmpty() -> EmptyState(error ?: "")
            candidates.isEmpty() -> EmptyState("No candidates found. Try a different search.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(candidates, key = { it.track.sourceId }) { c ->
                    val selected = c.track.sourceId == currentId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    container.matcher.setManualMatch(spotifyKey, c.track)
                                    container.resolver.invalidate(spotifyKey)
                                    currentId = c.track.sourceId
                                    if (container.player.state.value.nowPlaying?.key == spotifyKey) {
                                        container.player.reloadCurrent()
                                    }
                                    onBack()
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(c.track.artworkUrl, 48)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${c.track.artist} · ${formatDuration(c.track.durationSec)} · match ${(c.score * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selected) Icon(Icons.Filled.CheckCircle, "Current match", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
