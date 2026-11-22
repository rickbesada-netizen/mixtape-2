package app.mixtape.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.core.model.formatDuration
import app.mixtape.source.Pager
import app.mixtape.source.VideoItem
import app.mixtape.source.YouTubeWatch
import app.mixtape.source.YtItem
import app.mixtape.ui.components.CastButton
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.SmartSearch
import app.mixtape.ui.components.VideoCard
import app.mixtape.ui.components.VideoRowItem
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The chips along the top of Home, like YouTube's. */
private enum class HomeChip(val label: String, val kiosk: String? = null) {
    ALL("All"),
    TRENDING("Trending", "Trending"),
    MUSIC("Music", "trending_music"),
    GAMING("Gaming", "trending_gaming"),
    LIVE("Live", "live"),
    PODCASTS("Podcasts", "trending_podcasts_episodes"),
    MOVIES("Movies & shows", "trending_movies_and_shows"),
}

private enum class SubsChip(val label: String) { ALL("All"), TODAY("Today"), VIDEOS("Videos"), LIVE("Live") }

private enum class Section { HOME, SUBSCRIPTIONS, YOU }

/** Keeps lists while you move around the app, so coming back doesn't reload. */
private object VideosCache {
    val lists = HashMap<String, List<YtItem>>()
    val pagers = HashMap<String, Pager<YtItem>>()
    val published = HashMap<String, Long>()
    var shelf: List<VideoItem> = emptyList()
}

/**
 * The YouTube side of Mixtape, laid out like the YouTube app: Home (your channels, chips, feed and
 * a Shorts shelf), Shorts, Subscriptions and You (history, liked videos, Watch later). No ads.
 */
@Composable
fun VideosScreen(
    openVideo: (String) -> Unit,
    openChannel: (String) -> Unit,
    openSubscriptions: () -> Unit,
    openShorts: () -> Unit = {},
    openMusic: () -> Unit = {},
    openDownloads: () -> Unit = {},
    openSettings: () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = searchOpen) { searchOpen = false }
    androidx.activity.compose.BackHandler(enabled = !searchOpen && section != Section.HOME) { section = Section.HOME }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (searchOpen) {
                SearchMode(openVideo, openChannel, onClose = { searchOpen = false })
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopBar(onSearch = { searchOpen = true })
                    when (section) {
                        Section.HOME -> HomeSection(openVideo, openChannel, openSubscriptions, openShorts)
                        Section.SUBSCRIPTIONS -> SubscriptionsSection(openVideo, openChannel, openSubscriptions)
                        Section.YOU -> YouSection(openVideo, openChannel, openSubscriptions, openDownloads, openSettings)
                    }
                }
            }
        }
        BottomBar(
            section = section,
            onSection = { section = it; searchOpen = false },
            openShorts = openShorts,
            openMusic = openMusic,
        )
    }
}

@Composable
private fun TopBar(onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Mixtape's own mark (not YouTube's logo).
        Box(
            Modifier.size(width = 30.dp, height = 22.dp).clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFF4D6D), Color(0xFF8E2DE2)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(6.dp))
        Text("Mixtape", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        CastButton()
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") }
    }
}

@Composable
private fun BottomBar(section: Section, onSection: (Section) -> Unit, openShorts: () -> Unit, openMusic: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarItem(if (section == Section.HOME) Icons.Filled.Home else Icons.Outlined.Home, "Home", section == Section.HOME) { onSection(Section.HOME) }
                BarItem(Icons.Filled.Whatshot, "Shorts", false, openShorts)
                BarItem(Icons.Filled.LibraryMusic, "Music", false, openMusic)
                BarItem(
                    if (section == Section.SUBSCRIPTIONS) Icons.Filled.Subscriptions else Icons.Outlined.Subscriptions,
                    "Subscriptions", section == Section.SUBSCRIPTIONS,
                ) { onSection(Section.SUBSCRIPTIONS) }
                BarItem(Icons.Filled.History, "You", section == Section.YOU) { onSection(Section.YOU) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** YouTube-style chip: white when picked, grey otherwise. */
@Composable
private fun YtChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** Your channels as a row of round avatars, like the top of YouTube. */
@Composable
private fun ChannelsRow(openChannel: (String) -> Unit, openAll: () -> Unit) {
    val container = LocalContainer.current
    val subsFlow = remember { container.subscriptions.subscriptions() }
    val subs by subsFlow.collectAsStateWithLifecycle(emptyList())
    if (subs.isEmpty()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(subs, key = { it.channelId }) { s ->
                Column(Modifier.width(64.dp).clickable { openChannel(s.channelId) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    ChannelAvatar(s.avatarUrl, 56)
                    Text(s.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        TextButton(onClick = openAll) { Text("All", color = Color(0xFF3EA6FF)) }
    }
}

/** Shorts in a row of tall thumbnails, part of the Home feed like YouTube's shelf. */
@Composable
private fun ShortsShelf(shorts: List<VideoItem>, onOpen: (VideoItem) -> Unit) {
    if (shorts.isEmpty()) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Whatshot, null, tint = Color(0xFFFF0033))
            Spacer(Modifier.width(6.dp))
            Text("Shorts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shorts, key = { "sh" + it.id }) { v ->
                Box(
                    Modifier.width(150.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen(v) },
                ) {
                    if (v.thumbnail != null) AsyncImage(model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)), startY = 300f)))
                    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                        Text(v.title, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (v.views > 0) Text(app.mixtape.ui.components.viewsText(v.views), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeSection(
    openVideo: (String) -> Unit,
    openChannel: (String) -> Unit,
    openSubscriptions: () -> Unit,
    openShorts: () -> Unit,
) {
    val container = LocalContainer.current
    val watch = container.youtubeWatch
    val history by container.videoLibrary.history.collectAsStateWithLifecycle()
    var chip by rememberSaveable { mutableStateOf(HomeChip.ALL) }
    var reload by remember { mutableIntStateOf(0) }
    val key = "home:" + chip.name
    var items by remember(key, reload) { mutableStateOf(VideosCache.lists[key].orEmpty()) }
    var loading by remember(key, reload) { mutableStateOf(false) }
    var error by remember(key, reload) { mutableStateOf<String?>(null) }
    var shelf by remember { mutableStateOf(VideosCache.shelf) }

    suspend fun loadMore() {
        val pager = VideosCache.pagers[key] ?: return
        if (pager.done || loading) return
        loading = true
        runCatching { pager.load() }
            .onSuccess { more ->
                val seen = items.map { itemKey(it) }.toHashSet()
                items = items + more.filter { it !is YtItem.Video || !it.v.isShort }.filter { seen.add(itemKey(it)) }
                VideosCache.lists[key] = items
            }
            .onFailure { error = friendly(it) }
        loading = false
    }

    LaunchedEffect(key, reload) {
        if (VideosCache.lists[key] != null && reload == 0) return@LaunchedEffect
        error = null
        if (chip.kiosk != null) {
            VideosCache.pagers[key] = watch.kiosk(chip.kiosk!!)
            items = emptyList()
            loadMore()
        } else {
            loading = true
            val mine = container.videoLibrary.forYou()
            val subs = runCatching { subscriptionVideos(container, force = false) }.getOrDefault(emptyList()).take(15)
            var list = interleave(mine, subs)
            val pager = watch.kiosk("Trending")
            VideosCache.pagers[key] = pager
            if (list.size < 25) {
                val trending = runCatching { pager.load() }.getOrDefault(emptyList()).filterIsInstance<YtItem.Video>().map { it.v }
                list = (list + trending).distinctBy { it.id }
            }
            items = list.filter { !it.isShort }.map { YtItem.Video(it) }
            VideosCache.lists[key] = items
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        if (shelf.isEmpty()) {
            val found = runCatching { watch.search("#shorts", YouTubeWatch.SearchKind.VIDEOS).load() }.getOrDefault(emptyList())
                .filterIsInstance<YtItem.Video>().map { it.v }.filter { it.isShort || it.durationSec in 1..70 }.take(12)
            shelf = found
            VideosCache.shelf = found
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, key) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 4) loadMore() }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item { ChannelsRow(openChannel, openSubscriptions) }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { HomeChip.entries.forEach { c -> YtChip(c.label, chip == c) { chip = c } } }
        }
        error?.let { e ->
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(e, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { VideosCache.lists.remove(key); reload++ }) { Text("Try again") }
                }
            }
        }
        itemsIndexed(items, key = { _, it -> itemKey(it) }) { i, item ->
            if (item is YtItem.Video) {
                VideoCard(
                    item.v, history.firstOrNull { it.video.id == item.v.id }?.progress ?: 0f,
                    onOpen = { openVideo(item.v.id) },
                    onChannel = item.v.channelId?.let { id -> { openChannel(id) } },
                )
            }
            // The Shorts shelf sits a few videos down, like on YouTube.
            if (i == 2 && chip == HomeChip.ALL) ShortsShelf(shelf) { ShortsLaunch.first = it; openShorts() }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
    }
}

// ---------------------------------------------------------------- Subscriptions

@Composable
private fun SubscriptionsSection(openVideo: (String) -> Unit, openChannel: (String) -> Unit, openSubscriptions: () -> Unit) {
    val container = LocalContainer.current
    val history by container.videoLibrary.history.collectAsStateWithLifecycle()
    var chip by rememberSaveable { mutableStateOf(SubsChip.ALL) }
    var reload by remember { mutableIntStateOf(0) }
    var videos by remember { mutableStateOf(VideosCache.lists["subs"].orEmpty().filterIsInstance<YtItem.Video>().map { it.v }) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        if (VideosCache.lists["subs"] != null && reload == 0) return@LaunchedEffect
        loading = true
        runCatching { subscriptionVideos(container, force = reload > 0) }
            .onSuccess { v -> videos = v; VideosCache.lists["subs"] = v.map { YtItem.Video(it) } }
            .onFailure { error = friendly(it) }
        loading = false
    }

    val dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    val shown = when (chip) {
        SubsChip.ALL -> videos
        SubsChip.TODAY -> videos.filter { (VideosCache.published[it.id] ?: 0L) >= dayAgo }
        SubsChip.VIDEOS -> videos.filter { !it.isShort && !it.isLive }
        SubsChip.LIVE -> videos.filter { it.isLive }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ChannelsRow(openChannel, openSubscriptions) }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SubsChip.entries.forEach { c -> YtChip(c.label, chip == c) { chip = c } }
                TextButton(onClick = { reload++ }) { Text("Refresh", color = Color(0xFF3EA6FF)) }
            }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        if (!loading && shown.isEmpty()) {
            item {
                Text(
                    if (videos.isEmpty()) "Subscribe to channels (or import your YouTube subscriptions under All) to see their new videos here."
                    else "Nothing here yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(shown, key = { "sv" + it.id }) { v ->
            VideoCard(
                v, history.firstOrNull { it.video.id == v.id }?.progress ?: 0f,
                onOpen = { openVideo(v.id) },
                onChannel = v.channelId?.let { id -> { openChannel(id) } },
            )
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
    }
}

// ---------------------------------------------------------------- You

private enum class YouList(val title: String) { HISTORY("History"), LIKED("Liked videos"), LATER("Watch later") }

@Composable
private fun YouSection(
    openVideo: (String) -> Unit,
    openChannel: (String) -> Unit,
    openSubscriptions: () -> Unit,
    openDownloads: () -> Unit,
    openSettings: () -> Unit,
) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val account by container.cloud.account.collectAsStateWithLifecycle()
    val history by container.videoLibrary.history.collectAsStateWithLifecycle()
    val laterFlow = remember { container.videoLibrary.watchLater() }
    val later by laterFlow.collectAsStateWithLifecycle(emptyList())
    val likedFlow = remember { container.videoLibrary.likedVideos() }
    val liked by likedFlow.collectAsStateWithLifecycle(emptyList())
    val subsFlow = remember { container.subscriptions.subscriptions() }
    val subs by subsFlow.collectAsStateWithLifecycle(emptyList())
    var open by rememberSaveable { mutableStateOf<YouList?>(null) }

    androidx.activity.compose.BackHandler(enabled = open != null) { open = null }

    val list = open
    if (list != null) {
        val videos = when (list) {
            YouList.HISTORY -> history.map { it.video }
            YouList.LIKED -> liked
            YouList.LATER -> later
        }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(list.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (list == YouList.HISTORY && history.isNotEmpty()) TextButton(onClick = { container.videoLibrary.clear() }) { Text("Clear all") }
            }
            if (videos.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(onClick = {
                        app.mixtape.source.VideoQueue.set(list.title, videos)
                        openVideo(videos.first().id)
                    }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onSurface) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.surface)
                            Text("Play all", color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (videos.isEmpty()) item { Text("Nothing here yet", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(videos, key = { list.name + it.id }) { v ->
                    val remove: Pair<String, () -> Unit> = when (list) {
                        YouList.HISTORY -> "Remove from history" to { container.videoLibrary.remove(v.id) }
                        YouList.LIKED -> "Remove from Liked videos" to { scope.launch { container.videoLibrary.toggleLiked(v) }; Unit }
                        YouList.LATER -> "Remove from Watch later" to { scope.launch { container.videoLibrary.toggleWatchLater(v) }; Unit }
                    }
                    VideoRowItem(v, history.firstOrNull { it.video.id == v.id }?.progress ?: 0f, onOpen = { openVideo(v.id) }, extraMenu = listOf(remove))
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val name = account?.email?.substringBefore('@') ?: "You"
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFF4D6D), Color(0xFF8E2DE2)))),
                    contentAlignment = Alignment.Center,
                ) { Text(name.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        account?.email ?: "Log in under Settings to sync across phones",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${subs.size} subscriptions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YtChip("Manage subscriptions", false, openSubscriptions)
                YtChip("Downloads", false, openDownloads)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable { open = YouList.HISTORY }.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, Modifier.size(16.dp))
            }
            if (history.isEmpty()) {
                Text("Videos you watch show up here", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(history.take(20), key = { "h" + it.video.id }) { e ->
                        Column(Modifier.width(160.dp).clickable { openVideo(e.video.id) }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                if (e.video.thumbnail != null) AsyncImage(model = e.video.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (e.video.durationSec > 0) {
                                    Text(
                                        formatDuration(e.video.durationSec), color = Color.White, style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                                    )
                                }
                                if (e.progress > 0.02f) {
                                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                                        Box(Modifier.fillMaxWidth(e.progress).height(3.dp).background(Color(0xFFFF0000)))
                                    }
                                }
                            }
                            Text(e.video.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text(e.video.channel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
        item {
            Text("Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
            LibraryRow(Icons.Outlined.ThumbUp, "Liked videos", "${liked.size} videos", liked.firstOrNull()?.thumbnail) { open = YouList.LIKED }
            LibraryRow(Icons.Filled.WatchLater, "Watch later", "${later.size} videos", later.firstOrNull()?.thumbnail) { open = YouList.LATER }
            LibraryRow(Icons.Filled.History, "History", "${history.size} videos", history.firstOrNull()?.video?.thumbnail) { open = YouList.HISTORY }
            LibraryRow(Icons.Filled.Download, "Downloads", "Videos and songs on this phone", null, openDownloads)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LibraryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, thumb: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(3.dp)) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Search

@Composable
private fun SearchMode(openVideo: (String) -> Unit, openChannel: (String) -> Unit, onClose: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val history by container.videoLibrary.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf(YouTubeWatch.SearchKind.ALL) }
    val key = searching?.let { "search:${kind.name}:$it" }
    var items by remember(key) { mutableStateOf(key?.let { VideosCache.lists[it] }.orEmpty()) }
    var loading by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }

    suspend fun loadMore() {
        val k = key ?: return
        val pager = VideosCache.pagers[k] ?: return
        if (pager.done || loading) return
        loading = true
        runCatching { pager.load() }
            .onSuccess { more ->
                val seen = items.map { itemKey(it) }.toHashSet()
                items = items + more.filter { seen.add(itemKey(it)) }
                VideosCache.lists[k] = items
            }
            .onFailure { error = friendly(it) }
        loading = false
    }

    LaunchedEffect(key) {
        val q = searching ?: return@LaunchedEffect
        val k = key ?: return@LaunchedEffect
        if (VideosCache.lists[k] != null) return@LaunchedEffect
        VideosCache.pagers[k] = container.youtubeWatch.search(q, kind)
        items = emptyList()
        loadMore()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, key) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 4) loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Search YouTube", style = MaterialTheme.typography.titleMedium)
        }
        SmartSearch(
            box = "videos",
            query = query,
            onQueryChange = { query = it },
            onSubmit = { q ->
                val pastedId = VideoItem.idFrom(q)
                if (pastedId != null) openVideo(pastedId)
                else { VideosCache.lists.remove("search:${kind.name}:$q"); searching = q }
            },
            placeholder = "Search YouTube",
        ) {
            Column(Modifier.fillMaxSize()) {
                if (searching != null) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) { YouTubeWatch.SearchKind.entries.forEach { k -> YtChip(k.label, kind == k) { kind = k } } }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                    if (searching == null) {
                        item { Text("Search for videos, channels and playlists. Tap the mic to search by voice.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                    }
                    items(items, key = { itemKey(it) }) { item ->
                        when (item) {
                            is YtItem.Video -> VideoCard(
                                item.v, history.firstOrNull { it.video.id == item.v.id }?.progress ?: 0f,
                                onOpen = { openVideo(item.v.id) },
                                onChannel = item.v.channelId?.let { id -> { openChannel(id) } },
                            )
                            is YtItem.ChannelHit -> {
                                val idsFlow = remember { container.subscriptions.subscribedIds() }
                                val subbed by idsFlow.collectAsStateWithLifecycle(emptySet())
                                ChannelResultRow(
                                    item.c, item.c.id in subbed,
                                    onOpen = { openChannel(item.c.id) },
                                    onToggle = {
                                        scope.launch {
                                            if (item.c.id in subbed) container.subscriptions.unsubscribe(item.c.id)
                                            else container.subscriptions.subscribe(item.c)
                                        }
                                    },
                                )
                            }
                            is YtItem.PlaylistHit -> VideoRowItem(
                                VideoItem("playlist", item.p.title, item.p.subtitle, thumbnail = item.p.artworkUrl),
                                0f,
                                onOpen = { actions.openCollection(item.p.url) },
                            )
                        }
                    }
                    if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

private fun itemKey(i: YtItem): String = when (i) {
    is YtItem.Video -> "v:" + i.v.id
    is YtItem.ChannelHit -> "c:" + i.c.id
    is YtItem.PlaylistHit -> "p:" + i.p.url
}

private fun friendly(e: Throwable): String {
    if (app.mixtape.core.net.YouTubeLimits.isBotBlock(e)) {
        app.mixtape.core.net.YouTubeLimits.markBlocked()
        return "YouTube is asking to confirm you're not a bot. Wait a few minutes and try again."
    }
    return "Couldn't load: " + (e.message ?: e.javaClass.simpleName)
}

private fun interleave(a: List<VideoItem>, b: List<VideoItem>): List<VideoItem> {
    val out = mutableListOf<VideoItem>()
    val seen = HashSet<String>()
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        // Two recommendations for every new upload from your channels.
        listOfNotNull(a.getOrNull(i * 2), a.getOrNull(i * 2 + 1), b.getOrNull(i)).forEach { if (seen.add(it.id)) out += it }
    }
    a.drop(n * 2).forEach { if (seen.add(it.id)) out += it }
    return out
}

/** New uploads from the channels you follow, newest first. */
private suspend fun subscriptionVideos(container: app.mixtape.AppContainer, force: Boolean): List<VideoItem> {
    val repo = container.subscriptions
    val avatars = repo.subscriptions().first().associate { it.channelId to it.avatarUrl }
    return repo.feed(force).map { f ->
        VideosCache.published[f.track.sourceId] = f.publishedAt
        VideoItem(
            id = f.track.sourceId,
            title = f.track.title,
            channel = f.channelName,
            channelUrl = "https://www.youtube.com/channel/${f.channelId}",
            thumbnail = f.track.artworkUrl,
            durationSec = f.track.durationSec,
            ago = if (f.publishedAt > 0) DateUtils.getRelativeTimeSpanString(f.publishedAt).toString() else "",
            avatar = avatars[f.channelId],
        )
    }
}
