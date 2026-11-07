package app.mixtape.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mixtape.source.Channel
import app.mixtape.source.FeedVideo
import app.mixtape.ui.components.LocalContainer
import app.mixtape.ui.components.LocalTrackActions
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun ChannelAvatar(url: String?, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Person, null)
    }
}

/** A full-width YouTube-style video row: thumbnail, title, channel · time ago. */
@Composable
fun FeedVideoItem(v: FeedVideo, onOpen: () -> Unit, onChannel: (() -> Unit)?) {
    val actions = LocalTrackActions.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = v.track.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(v.track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val ago = if (v.publishedAt > 0) DateUtils.getRelativeTimeSpanString(v.publishedAt).toString() else ""
                Text(
                    listOf(v.channelName, ago).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier,
                )
            }
            IconButton(onClick = { actions.play(listOf(v.track), 0) }) { Icon(Icons.Filled.Headphones, "Listen as audio") }
        }
    }
}

@Composable
fun SubscriptionsScreen(onBack: () -> Unit, openChannel: (String) -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.subscriptions
    val subsFlow = remember { repo.subscriptions() }
    val subs by subsFlow.collectAsStateWithLifecycle(emptyList())
    var feed by remember { mutableStateOf(repo.peekFeed()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(force: Boolean) {
        loading = true
        scope.launch {
            runCatching { repo.feed(force) }.onSuccess { feed = it; error = null }.onFailure { error = it.message }
            loading = false
            runCatching { repo.fillAvatars() }
        }
    }
    LaunchedEffect(subs.size) { if (subs.isNotEmpty()) load(force = false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { repo.importFile(uri) }
                .onSuccess { Toast.makeText(context, "Added $it channels", Toast.LENGTH_SHORT).show(); load(force = true) }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("Subscriptions") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = { load(force = true) }, enabled = !loading && subs.isNotEmpty()) { Icon(Icons.Filled.Refresh, "Refresh") }
                IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.UploadFile, "Import subscriptions") }
            },
        )
        if (subs.isEmpty()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Follow YouTube channels to see their new videos here.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Subscribe from any video (tap Subscribe under it), search with the Channels filter, " +
                        "or bring your YouTube subscriptions across: Google Takeout > YouTube and YouTube Music > " +
                        "subscriptions.csv (a NewPipe export works too).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import subscriptions file") }
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            item {
                LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(subs, key = { it.channelId }) { s ->
                        Column(
                            Modifier.width(64.dp).clickable { openChannel(s.channelId) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ChannelAvatar(s.avatarUrl, 52)
                            Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            error?.let { item { Text("Couldn't refresh: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (feed.isEmpty() && loading) {
                item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
            items(feed, key = { it.track.key }) { v ->
                FeedVideoItem(v, onOpen = { actions.watchVideo(v.track) }, onChannel = { openChannel(v.channelId) })
            }
        }
    }
}

@Composable
fun ChannelScreen(channelId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalTrackActions.current
    val scope = rememberCoroutineScope()
    val repo = container.subscriptions
    val idsFlow = remember { repo.subscribedIds() }
    val subscribed by idsFlow.collectAsStateWithLifecycle(emptySet())
    var channel by remember { mutableStateOf<Channel?>(null) }
    var videos by remember { mutableStateOf<List<FeedVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(channelId) {
        runCatching { repo.channelVideos(channelId) }.onSuccess { videos = it }.onFailure { error = it.message }
        loading = false
        runCatching { repo.channelInfo("https://www.youtube.com/channel/$channelId") }.onSuccess { channel = it }
    }
    val name = channel?.name ?: videos.firstOrNull()?.channelName ?: ""
    val isSubbed = channelId in subscribed

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(channel?.avatarUrl, 64)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        channel?.subscribers?.takeIf { it > 0 }?.let {
                            Text(compactCount(it) + " subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (isSubbed) {
                        OutlinedButton(onClick = { scope.launch { repo.unsubscribe(channelId) } }) { Text("Subscribed") }
                    } else {
                        Button(onClick = {
                            scope.launch { repo.subscribe(Channel(channelId, name, channel?.avatarUrl)) }
                        }, enabled = name.isNotBlank()) { Text("Subscribe") }
                    }
                }
                if (videos.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { actions.play(videos.map { it.track }, 0) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Icon(Icons.Filled.Headphones, null, Modifier.size(18.dp)); Text("  Listen to latest uploads") }
                }
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            error?.let { item { Text("Couldn't load this channel: $it", modifier = Modifier.padding(16.dp)) } }
            item { Spacer(Modifier.size(8.dp)) }
            items(videos, key = { it.track.key }) { v -> FeedVideoItem(v, onOpen = { actions.watchVideo(v.track) }, onChannel = null) }
        }
    }
}

/** A row of channel search results. */
@Composable
fun ChannelResultRow(c: Channel, subscribed: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(c.avatarUrl, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (c.subscribers > 0) {
                Text(compactCount(c.subscribers) + " subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (subscribed) OutlinedButton(onClick = onToggle) { Text("Subscribed") }
        else Button(onClick = onToggle) { Text("Subscribe") }
    }
}

fun compactCount(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}.replace(".0", "")
