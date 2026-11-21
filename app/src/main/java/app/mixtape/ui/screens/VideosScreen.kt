package app.mixtape.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.source.Pager
import app.mixtape.source.VideoItem
import app.mixtape.source.YouTubeWatch
import app.mixtape.source.YtItem
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import app.mixtape.ui.components.VideoCard
import app.mixtape.ui.components.VideoRowItem
import app.mixtape.ui.components.SmartSearch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class VideoTab(val label: String, val kiosk: String? = null) {
    FOR_YOU("For you"),
    SUBSCRIPTIONS("Subscriptions"),
    TRENDING("Trending", "Trending"),
    MUSIC("Music", "trending_music"),
    GAMING("Gaming", "trending_gaming"),
    LIVE("Live", "live"),
    PODCASTS("Podcasts", "trending_podcasts_episodes"),
    MOVIES("Movies & shows", "trending_movies_and_shows"),
    LATER("Watch later"),
    HISTORY("History"),
}

/** Keeps each tab's list while you move around the app, so coming back doesn't reload. */
private object VideosCache {
    val lists = HashMap<String, List<YtItem>>()
    val pagers = HashMap<String, Pager<YtItem>>()
}

/** The YouTube side of Mixtape: no ads, with subscriptions, trending, search and your history. */
@Composable
fun VideosScreen(
    openVideo: (String) -> Unit,
    openChannel: (String) -> Unit,
    openSubscriptions: () -> Unit,
    openShorts: () -> Unit = {},
) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val watch = container.youtubeWatch
    val history by container.videoLibrary.history.collectAsStateWithLifecycle()
    val laterFlow = remember { container.videoLibrary.watchLater() }
    val later by laterFlow.collectAsStateWithLifecycle(emptyList())

    var tab by remember { mutableStateOf(VideoTab.FOR_YOU) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(YouTubeWatch.SearchKind.VIDEOS) }
    var reload by remember { mutableIntStateOf(0) }

    val key = searching?.let { "search:${kind.name}:$it" } ?: tab.name
    var items by remember(key, reload) { mutableStateOf(VideosCache.lists[key].orEmpty()) }
    var loading by remember(key, reload) { mutableStateOf(false) }
    var error by remember(key, reload) { mutableStateOf<String?>(null) }

    fun progressOf(id: String) = history.firstOrNull { it.video.id == id }?.progress ?: 0f

    suspend fun loadMore() {
        val pager = VideosCache.pagers[key] ?: return
        if (pager.done || loading) return
        loading = true
        runCatching { pager.load() }
            .onSuccess { more ->
                val seen = items.map { itemKey(it) }.toHashSet()
                items = items + more.filter { seen.add(itemKey(it)) }
                VideosCache.lists[key] = items
            }
            .onFailure { error = friendly(it) }
        loading = false
    }

    LaunchedEffect(key, reload) {
        if (VideosCache.lists[key] != null && reload == 0) return@LaunchedEffect
        error = null
        val s = searching
        when {
            s != null -> { VideosCache.pagers[key] = watch.search(s, kind); items = emptyList(); loadMore() }
            tab.kiosk != null -> { VideosCache.pagers[key] = watch.kiosk(tab.kiosk!!); items = emptyList(); loadMore() }
            tab == VideoTab.SUBSCRIPTIONS -> {
                loading = true
                runCatching { subscriptionVideos(container, force = reload > 0) }
                    .onSuccess { items = it.map { v -> YtItem.Video(v) }; VideosCache.lists[key] = items }
                    .onFailure { error = friendly(it) }
                loading = false
            }
            tab == VideoTab.FOR_YOU -> {
                loading = true
                val mine = container.videoLibrary.forYou()
                val subs = runCatching { subscriptionVideos(container, force = false) }.getOrDefault(emptyList()).take(15)
                var list = interleave(mine, subs)
                if (list.size < 25) {
                    // Not much history yet: fill up with what's trending.
                    val pager = watch.kiosk("Trending")
                    VideosCache.pagers[key] = pager
                    val trending = runCatching { pager.load() }.getOrDefault(emptyList())
                        .filterIsInstance<YtItem.Video>().map { it.v }
                    list = (list + trending).distinctBy { it.id }
                }
                items = list.map { YtItem.Video(it) }
                VideosCache.lists[key] = items
                loading = false
            }
            else -> Unit // Watch later / History come straight from your library
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, key) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 4) loadMore() }
    }

    SmartSearch(
        box = "videos",
        query = query,
        onQueryChange = { query = it },
        onSubmit = { q ->
            val pastedId = VideoItem.idFrom(q)
            if (pastedId != null) {
                openVideo(pastedId) // a pasted YouTube link opens straight away
            } else {
                VideosCache.lists.remove("search:${kind.name}:$q")
                searching = q
            }
        },
        placeholder = "Search YouTube",
    ) {
    Column(Modifier.fillMaxSize()) {
        if (searching != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Results for \"$searching\"", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { searching = null; query = "" }) { Text("Back to feed") }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (searching != null) {
                YouTubeWatch.SearchKind.entries.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                }
            } else {
                VideoTab.entries.forEach { t ->
                    FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                    if (t == VideoTab.FOR_YOU) {
                        // Shorts open full screen, swipe up for the next one.
                        FilterChip(selected = false, onClick = openShorts, label = { Text("Shorts") })
                    }
                }
            }
        }

        val shown: List<YtItem> = when {
            searching != null -> items
            tab == VideoTab.LATER -> later.map { YtItem.Video(it) }
            tab == VideoTab.HISTORY -> history.map { YtItem.Video(it.video) }
            else -> items
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (searching == null && tab == VideoTab.SUBSCRIPTIONS) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { reload++ }) { Text("Refresh") }
                        TextButton(onClick = openSubscriptions) { Text("Manage channels") }
                    }
                }
            }
            if (searching == null && tab == VideoTab.HISTORY && history.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { container.videoLibrary.clear() }) { Text("Clear history") }
                    }
                }
            }
            error?.let { e ->
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(e, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { VideosCache.lists.remove(key); reload++ }) { Text("Try again") }
                    }
                }
            }
            if (shown.isEmpty() && !loading && error == null) {
                item {
                    Text(
                        when {
                            searching != null -> "No results"
                            tab == VideoTab.LATER -> "Nothing saved yet. Use ⋮ > Save to Watch later on any video."
                            tab == VideoTab.HISTORY -> "Videos you watch show up here, and you can carry on where you stopped."
                            tab == VideoTab.SUBSCRIPTIONS -> "Subscribe to channels to see their new videos here."
                            else -> "Nothing here right now"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(shown, key = { itemKey(it) }) { item ->
                when (item) {
                    is YtItem.Video -> {
                        val v = item.v
                        val extra = when {
                            searching == null && tab == VideoTab.HISTORY -> listOf("Remove from history" to { container.videoLibrary.remove(v.id) })
                            searching == null && tab == VideoTab.LATER -> listOf("Remove from Watch later" to { scope.launch { container.videoLibrary.toggleWatchLater(v) }; Unit })
                            else -> emptyList()
                        }
                        VideoCard(
                            v, progressOf(v.id),
                            onOpen = { openVideo(v.id) },
                            onChannel = v.channelId?.let { id -> { openChannel(id) } },
                            extraMenu = extra,
                        )
                    }
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
                    is YtItem.PlaylistHit -> {
                        val p = item.p
                        VideoRowItem(
                            VideoItem("playlist", p.title, p.subtitle, thumbnail = p.artworkUrl),
                            0f,
                            onOpen = { actions.openCollection(p.url) },
                        )
                    }
                }
            }
            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
            }
        }
    }
}
}

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
