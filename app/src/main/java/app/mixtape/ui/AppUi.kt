package app.mixtape.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mixtape.AppContainer
import app.mixtape.core.model.Track
import app.mixtape.player.PlayerUiState
import app.mixtape.ui.components.Artwork
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.PlaylistPickerDialog
import app.mixtape.ui.components.TrackActions
import app.mixtape.source.LinkResult
import app.mixtape.ui.screens.CollectionScreen
import app.mixtape.ui.screens.DownloadsScreen
import app.mixtape.ui.screens.LibraryScreen
import app.mixtape.ui.screens.MatchScreen
import app.mixtape.ui.screens.NowPlayingScreen
import app.mixtape.ui.screens.PlaylistScreen
import app.mixtape.ui.screens.SearchScreen
import app.mixtape.ui.screens.SettingsScreen
import app.mixtape.ui.screens.SmartListScreen
import app.mixtape.ui.screens.SpotifyScreen
import app.mixtape.ui.screens.VideoScreen
import app.mixtape.core.model.SourceType
import kotlinx.coroutines.launch
import java.net.URLEncoder

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("search", "Search", Icons.Filled.Search),
    Tab("library", "Library", Icons.Filled.LibraryMusic),
    Tab("downloads", "Downloads", Icons.Filled.Download),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun MixtapeAppUi(container: AppContainer) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pickerFor by remember { mutableStateOf<Track?>(null) }
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val playlistsFlow = remember { container.library.playlists() }

    LaunchedEffect(Unit) {
        container.player.errors.collect { snackbar.showSnackbar(it) }
    }

    val actions = remember {
        object : TrackActions {
            override fun play(list: List<Track>, index: Int) {
                // Tracks must exist in the library so history, likes and Spotify matching work.
                scope.launch { container.library.saveTracks(list) }
                container.player.playTracks(list, index)
            }
            override fun playNext(track: Track) {
                scope.launch { container.library.saveTracks(listOf(track)) }
                container.player.playNext(track)
                scope.launch { snackbar.showSnackbar("Playing next: ${track.title}") }
            }
            override fun addToQueue(track: Track) {
                scope.launch { container.library.saveTracks(listOf(track)) }
                container.player.addToQueue(track)
                scope.launch { snackbar.showSnackbar("Added to queue") }
            }
            override fun toggleLike(track: Track, liked: Boolean) {
                scope.launch { container.library.setLiked(track, liked) }
            }
            override fun download(tracks: List<Track>) {
                container.downloads.enqueue(tracks)
                scope.launch {
                    snackbar.showSnackbar(if (tracks.size == 1) "Downloading ${tracks[0].title}" else "Downloading ${tracks.size} tracks")
                }
            }
            override fun addToPlaylist(track: Track) {
                pickerFor = track
            }
            override fun openLink(text: String) {
                scope.launch {
                    val wait = launch { snackbar.showSnackbar("Opening link...") }
                    runCatching { container.links.resolve(text) }
                        .onSuccess { r ->
                            wait.cancel()
                            when (r) {
                                is LinkResult.Single -> {
                                    container.library.saveTracks(listOf(r.track))
                                    container.player.playTracks(listOf(r.track), 0)
                                    snackbar.showSnackbar("Playing ${r.track.title}")
                                }
                                is LinkResult.Collection -> openCollection(r.detail.info.url)
                            }
                        }
                        .onFailure {
                            wait.cancel()
                            snackbar.showSnackbar(it.message ?: "Couldn't open that link")
                        }
                }
            }
            override fun watchVideo(track: Track) {
                scope.launch { container.library.saveTracks(listOf(track)) }
                nav.navigate("video/" + URLEncoder.encode(track.sourceId, "UTF-8"))
            }
            override fun downloadVideo(track: Track) {
                val video = track.copy(source = SourceType.YOUTUBE_VIDEO)
                container.downloads.enqueue(listOf(video))
                scope.launch {
                    snackbar.showSnackbar("Downloading video (up to ${container.settings.videoQuality.value}p): ${track.title}")
                }
            }
            override fun openCollection(url: String) {
                nav.navigate("collection/" + URLEncoder.encode(url, "UTF-8"))
            }
            override fun fixMatch(track: Track) {
                scope.launch {
                    container.library.saveTracks(listOf(track))
                    nav.navigate("match/" + URLEncoder.encode(track.key, "UTF-8"))
                }
            }
        }
    }

    // Links shared into the app (Share > Mixtape) open as soon as the UI is up.
    val pendingLink by container.pendingLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        container.pendingLink.value = null
        actions.openLink(link)
    }

    CompositionLocalProvider(LocalContainer provides container, LocalTrackActions provides actions) {
        val backStack by nav.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showChrome = route != "nowplaying" && route?.startsWith("video/") != true

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showChrome) {
                    Column {
                        if (playerState.nowPlaying != null) {
                            MiniPlayer(playerState, onOpen = { nav.navigate("nowplaying") })
                        }
                        NavigationBar {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = backStack?.destination?.route == tab.route,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "search",
                modifier = Modifier.padding(padding),
            ) {
                composable("search") { SearchScreen(openCollection = { actions.openCollection(it) }) }
                composable("collection/{url}") { entry ->
                    CollectionScreen(
                        url = java.net.URLDecoder.decode(entry.arguments?.getString("url") ?: "", "UTF-8"),
                        onBack = { nav.popBackStack() },
                        openSavedPlaylist = { nav.navigate("playlist/$it") },
                    )
                }
                composable("library") {
                    LibraryScreen(
                        openPlaylist = { nav.navigate("playlist/$it") },
                        openLiked = { nav.navigate("smart/liked") },
                        openRecent = { nav.navigate("smart/recent") },
                        openSpotify = { nav.navigate("spotify") },
                    )
                }
                composable("downloads") {
                    DownloadsScreen(openVideo = { id -> nav.navigate("video/" + URLEncoder.encode(id, "UTF-8")) })
                }
                composable("settings") { SettingsScreen() }
                composable("spotify") {
                    SpotifyScreen(
                        openPlaylist = { nav.navigate("playlist/$it") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "playlist/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    PlaylistScreen(
                        playlistId = entry.arguments?.getLong("id") ?: 0L,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("smart/{kind}") { entry ->
                    SmartListScreen(kind = entry.arguments?.getString("kind") ?: "liked", onBack = { nav.popBackStack() })
                }
                composable("match/{key}") { entry ->
                    MatchScreen(
                        spotifyKey = java.net.URLDecoder.decode(entry.arguments?.getString("key") ?: "", "UTF-8"),
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("video/{id}") { entry ->
                    VideoScreen(
                        videoId = java.net.URLDecoder.decode(entry.arguments?.getString("id") ?: "", "UTF-8"),
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("nowplaying") {
                    NowPlayingScreen(
                        onBack = { nav.popBackStack() },
                        onWatchVideo = { id -> nav.navigate("video/" + URLEncoder.encode(id, "UTF-8")) },
                        onFixMatch = { key ->
                            nav.navigate("match/" + URLEncoder.encode(key, "UTF-8"))
                        },
                    )
                }
            }
        }

        pickerFor?.let { track ->
            val playlists by playlistsFlow.collectAsStateWithLifecycle(emptyList())
            PlaylistPickerDialog(
                playlists = playlists,
                onPick = { id ->
                    scope.launch {
                        container.library.addToPlaylist(id, track)
                        snackbar.showSnackbar("Added to playlist")
                    }
                    pickerFor = null
                },
                onCreate = { name ->
                    scope.launch {
                        val id = container.library.createPlaylist(name)
                        container.library.addToPlaylist(id, track)
                        snackbar.showSnackbar("Added to $name")
                    }
                    pickerFor = null
                },
                onDismiss = { pickerFor = null },
            )
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit) {
    val container = LocalContainer.current
    val np = state.nowPlaying ?: return
    Surface(tonalElevation = 3.dp) {
        Column {
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(np.artworkUrl, 42)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.isBuffering) "Loading..." else np.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { container.player.togglePlay() }) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause")
                }
                IconButton(onClick = { container.player.next() }, enabled = state.hasNext) {
                    Icon(Icons.Filled.SkipNext, "Next")
                }
            }
        }
    }
}
