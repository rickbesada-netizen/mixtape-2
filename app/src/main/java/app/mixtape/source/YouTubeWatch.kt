package app.mixtape.source

import app.mixtape.core.model.RemoteCollection
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

/** A YouTube video in a list (feed, search, channel, up next). */
data class VideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val channelUrl: String? = null,
    val thumbnail: String? = null,
    val durationSec: Long = 0,
    val views: Long = -1,
    val ago: String = "",
    val avatar: String? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
) {
    val channelId: String? get() = channelUrl?.let { SubscriptionRepository.channelIdFrom(it) }

    fun toTrack() = Track(SourceType.YOUTUBE, id, title, channel, durationSec = durationSec, artworkUrl = thumbnail)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", title).put("c", channel).put("cu", channelUrl ?: "")
        .put("th", thumbnail ?: "").put("d", durationSec).put("v", views).put("a", ago).put("av", avatar ?: "")

    companion object {
        fun fromJson(o: JSONObject): VideoItem? {
            val id = o.optString("id").takeIf { it.length == 11 } ?: return null
            return VideoItem(
                id, o.optString("t"), o.optString("c"), o.optString("cu").ifBlank { null },
                o.optString("th").ifBlank { null }, o.optLong("d"), o.optLong("v", -1), o.optString("a"),
                o.optString("av").ifBlank { null },
            )
        }

        fun fromTrack(t: Track) = VideoItem(t.sourceId, t.title, t.artist, thumbnail = t.artworkUrl, durationSec = t.durationSec)

        private val ID = Regex("(?:v=|youtu\\.be/|/shorts/|/live/|/embed/)([\\w-]{11})")
        fun idFrom(url: String): String? = ID.find(url)?.groupValues?.get(1)
    }
}

/** Anything a YouTube list can hold. */
sealed interface YtItem {
    data class Video(val v: VideoItem) : YtItem
    data class ChannelHit(val c: Channel) : YtItem
    data class PlaylistHit(val p: RemoteCollection) : YtItem
}

data class Comment(
    val author: String,
    val avatar: String?,
    val text: String,
    val likes: Int,
    val ago: String,
    val pinned: Boolean,
    val hearted: Boolean,
    val replies: Int,
)

/** A time-coded caption line. */
data class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

/** Parts of a video SponsorBlock users marked as sponsor, self-promo, etc. */
data class SkipSegment(val startMs: Long, val endMs: Long, val category: String) {
    val label: String get() = when (category) {
        "sponsor" -> "sponsor"
        "selfpromo" -> "self-promotion"
        "interaction" -> "subscribe reminder"
        "intro" -> "intro"
        "outro" -> "end cards"
        "preview" -> "preview / recap"
        "music_offtopic" -> "non-music part"
        "filler" -> "filler"
        else -> category
    }
}

/**
 * Loads a list one page at a time (feeds, search, channel videos, comments). Keeps the NewPipe
 * extractor so "load more" continues where it left off.
 */
class Pager<T>(
    private val first: () -> ListExtractor.InfoItemsPage<*>,
    private val more: (Page) -> ListExtractor.InfoItemsPage<*>,
    private val map: (Any) -> T?,
) {
    private var next: Page? = null
    private var started = false
    var done = false
        private set

    suspend fun load(): List<T> = withContext(Dispatchers.IO) {
        if (done) return@withContext emptyList()
        val page = if (!started) first() else {
            val n = next ?: run { done = true; return@withContext emptyList() }
            more(n)
        }
        started = true
        next = page.nextPage
        if (!Page.isValid(next)) done = true
        page.items.mapNotNull { it?.let(map) }
    }
}

/** Everything video: feeds, search, channel pages, comments, dislikes, SponsorBlock, captions. */
class YouTubeWatch(private val http: OkHttpClient) {
    private val yt = ServiceList.YouTube

    // ---------------------------------------------------------------- lists

    /** Trending lists: "Trending", "trending_music", "trending_gaming", "trending_movies_and_shows", "trending_podcasts_episodes", "live". */
    fun kiosk(id: String): Pager<YtItem> {
        val ex = yt.kioskList.getExtractorById(id, null)
        return Pager({ ex.fetchPage(); ex.initialPage }, { ex.getPage(it) }, { toItem(it) })
    }

    enum class SearchKind(val filter: String, val label: String) {
        VIDEOS(YoutubeSearchQueryHandlerFactory.VIDEOS, "Videos"),
        CHANNELS(YoutubeSearchQueryHandlerFactory.CHANNELS, "Channels"),
        PLAYLISTS(YoutubeSearchQueryHandlerFactory.PLAYLISTS, "Playlists"),
        ALL("", "All"),
    }

    fun search(query: String, kind: SearchKind): Pager<YtItem> {
        val filters = if (kind == SearchKind.ALL) emptyList() else listOf(kind.filter)
        val ex = yt.getSearchExtractor(query, filters, "")
        return Pager({ ex.fetchPage(); ex.initialPage }, { ex.getPage(it) }, { toItem(it) })
    }

    /** All of a channel's videos (or shorts / live / playlists), newest first, page by page. */
    fun channelTab(channelId: String, tab: String = ChannelTabs.VIDEOS): Pager<YtItem> {
        var extractor: org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor? = null
        return Pager(
            first = {
                val info = ChannelInfo.getInfo(yt, "https://www.youtube.com/channel/$channelId")
                val handler = info.tabs.firstOrNull { it.contentFilters.contains(tab) }
                    ?: throw IOException("This channel has no ${tab.lowercase()}")
                val ex = yt.getChannelTabExtractor(handler)
                ex.fetchPage()
                extractor = ex
                ex.initialPage
            },
            more = { p -> extractor?.getPage(p) ?: throw IOException("Reload the channel") },
            map = { toItem(it) },
        )
    }

    fun comments(videoId: String): Pager<Comment> {
        val ex = yt.getCommentsExtractor("https://www.youtube.com/watch?v=$videoId")
        return Pager({ ex.fetchPage(); ex.initialPage }, { ex.getPage(it) }) { item ->
            (item as? CommentsInfoItem)?.let { c ->
                Comment(
                    author = c.uploaderName ?: "",
                    avatar = c.uploaderAvatars.bestUrl(),
                    text = htmlToText(c.commentText?.content.orEmpty()),
                    likes = c.likeCount.coerceAtLeast(0),
                    ago = c.textualUploadDate ?: "",
                    pinned = c.isPinned,
                    hearted = c.isHeartedByUploader,
                    replies = c.replyCount.coerceAtLeast(0),
                )
            }
        }
    }

    // ---------------------------------------------------------------- extras

    /** Like and dislike counts from Return YouTube Dislike (YouTube hides dislikes). */
    suspend fun votes(videoId: String): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
                .header("User-Agent", "Mixtape").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string().orEmpty())
                o.optLong("likes", -1) to o.optLong("dislikes", -1)
            }
        }.getOrNull()
    }

    /** Sponsor / self-promo / reminder parts from SponsorBlock, to skip automatically. */
    suspend fun skipSegments(videoId: String, categories: Set<String>): List<SkipSegment> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext emptyList()
        runCatching {
            val cats = JSONArray(categories.toList()).toString()
            val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=" +
                java.net.URLEncoder.encode(cats, "UTF-8")
            val req = Request.Builder().url(url).header("User-Agent", "Mixtape").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList() // 404 = nothing marked
                val arr = JSONArray(r.body?.string().orEmpty())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    if (o.optString("actionType", "skip") != "skip") return@mapNotNull null
                    val seg = o.optJSONArray("segment") ?: return@mapNotNull null
                    val s = (seg.optDouble(0) * 1000).toLong()
                    val e = (seg.optDouble(1) * 1000).toLong()
                    if (e - s < 1000) null else SkipSegment(s, e, o.optString("category"))
                }.sortedBy { it.startMs }
            }
        }.getOrDefault(emptyList())
    }

    /** Downloads and reads a WebVTT caption file. */
    suspend fun captions(url: String): List<CaptionCue> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Captions error ${r.code}")
            r.body?.string().orEmpty()
        }
        parseVtt(text)
    }

    companion object {
        private val TIME = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})")
        private val TAG = Regex("<[^>]+>")

        fun parseVtt(text: String): List<CaptionCue> {
            val out = mutableListOf<CaptionCue>()
            val blocks = text.replace("\r", "").split(Regex("\n\\s*\n"))
            for (b in blocks) {
                val lines = b.lines()
                val timing = lines.indexOfFirst { "-->" in it }
                if (timing < 0) continue
                val (a, z) = lines[timing].split("-->").map { it.trim() }
                val start = ms(a) ?: continue
                val end = ms(z.substringBefore(' ')) ?: continue
                val body = lines.drop(timing + 1).map { it.replace(TAG, "").trim() }.filter { it.isNotBlank() }
                if (body.isEmpty()) continue
                out += CaptionCue(start, end, body.joinToString("\n").replace("&amp;", "&").replace("&gt;", ">").replace("&lt;", "<").replace("&nbsp;", " "))
            }
            // Auto-generated captions repeat the previous line in each cue; keep only what's new.
            return out.mapIndexed { i, c ->
                val prev = out.getOrNull(i - 1)
                if (prev != null && c.text.startsWith(prev.text + "\n")) c.copy(text = c.text.removePrefix(prev.text + "\n")) else c
            }.filter { it.endMs - it.startMs > 50 }
        }

        private fun ms(s: String): Long? {
            val m = TIME.find(s) ?: return null
            val h = m.groupValues[1].toLongOrNull() ?: 0
            return ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].toLong()
        }

        fun htmlToText(html: String): String =
            androidx.core.text.HtmlCompat.fromHtml(html.replace("\n", "<br>"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString().trim()

        fun toVideo(s: StreamInfoItem): VideoItem? {
            val url = s.url ?: return null
            val id = VideoItem.idFrom(url) ?: runCatching { ServiceList.YouTube.streamLHFactory.getId(url) }.getOrNull() ?: return null
            return VideoItem(
                id = id,
                title = s.name ?: return null,
                channel = s.uploaderName?.removeSuffix(" - Topic") ?: "",
                channelUrl = s.uploaderUrl,
                thumbnail = s.thumbnails.bestUrl(),
                durationSec = s.duration.coerceAtLeast(0),
                views = s.viewCount,
                ago = s.textualUploadDate ?: "",
                avatar = s.uploaderAvatars.bestUrl(),
                isLive = s.streamType == StreamType.LIVE_STREAM,
                isShort = runCatching { s.isShortFormContent }.getOrDefault(false),
            )
        }

        fun toItem(item: Any): YtItem? = when (item) {
            is StreamInfoItem -> toVideo(item)?.let { YtItem.Video(it) }
            is ChannelInfoItem -> {
                val id = item.url?.let { SubscriptionRepository.channelIdFrom(it) }
                if (id == null) null else YtItem.ChannelHit(Channel(id, item.name ?: "", item.thumbnails.bestUrl(), item.subscriberCount))
            }
            is PlaylistInfoItem -> item.url?.let { url ->
                YtItem.PlaylistHit(
                    RemoteCollection(
                        url = url,
                        title = item.name ?: "Playlist",
                        subtitle = listOfNotNull(item.uploaderName, if (item.streamCount > 0) "${item.streamCount} videos" else null).joinToString(" · "),
                        artworkUrl = item.thumbnails.bestUrl(),
                        trackCount = item.streamCount,
                    )
                )
            }
            else -> null
        }
    }
}
