package app.mixtape.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mixtape.core.model.RemoteCollection
import app.mixtape.source.BrowseGroup
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions

/** Browse: genre / mood tiles, a playlist search across every platform, and Deezer's top 100. */
@Composable
fun BrowseScreen(onBack: () -> Unit, openCategory: (String) -> Unit, openSearch: (String) -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val browse = container.browse
    var query by remember { mutableStateOf("") }
    var top by remember { mutableStateOf(browse.cached("deezer-top")?.firstOrNull()?.playlists.orEmpty()) }
    LaunchedEffect(Unit) { top = runCatching { browse.topPlaylists() }.getOrDefault(top) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("Browse playlists") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Find playlists on every platform") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) openSearch(query.trim()) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Genres & moods", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(browse.categories, key = { it.id }) { c ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(c.color))
                        .clickable { openCategory(c.id) }
                        .padding(12.dp),
                ) {
                    Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (top.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Top ${top.size} playlists right now",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(top, key = { "top-" + it.url }) { p -> PlaylistTile(p) { actions.openCollection(p.url) } }
            }
        }
    }
}

/** One category (or a typed search): playlists from Deezer, YouTube Music, Spotify and SoundCloud. */
@Composable
fun BrowseCategoryScreen(categoryId: String?, query: String?, onBack: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val browse = container.browse
    val category = browse.categories.firstOrNull { it.id == categoryId }
    val key = category?.id ?: "q:${query.orEmpty()}"
    var groups by remember(key) { mutableStateOf(browse.cached(key)) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        if (groups == null) {
            runCatching { browse.load(category, category?.query ?: query.orEmpty()) }
                .onSuccess { groups = it; if (it.isEmpty()) error = "Nothing found. Check your internet and try again." }
                .onFailure { error = it.message }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(category?.title ?: "\"${query.orEmpty()}\" playlists") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        val g = groups
        when {
            g == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            g.isNullOrEmpty() -> Text(error ?: "Nothing found", modifier = Modifier.padding(24.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "${g.sumOf { it.playlists.size }} playlists",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                g.forEach { group: BrowseGroup ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-" + group.title) {
                        Text(
                            "From ${group.title} (${group.playlists.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(group.playlists, key = { group.title + it.url }) { p -> PlaylistTile(p) { actions.openCollection(p.url) } }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTile(p: RemoteCollection, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))) {
            Artwork(p.artworkUrl, 160, Modifier.fillMaxSize())
        }
        Text(
            p.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            p.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
