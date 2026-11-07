package app.mixtape.source

import android.content.Context
import android.net.Uri
import android.util.Xml
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.net.BROWSER_USER_AGENT
import app.mixtape.data.db.SubscriptionDao
import app.mixtape.data.db.SubscriptionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.time.OffsetDateTime

data class Channel(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val subscribers: Long = -1,
)

/** A video from a channel's RSS feed. */
data class FeedVideo(val track: Track, val channelId: String, val channelName: String, val publishedAt: Long)

/**
 * YouTube subscriptions without signing in to Google: you follow channels in Mixtape (or import
 * them from Google Takeout / NewPipe), and new uploads come from each channel's public RSS feed,
 * which is light and doesn't count against YouTube's bot checks like full page loads do.
 * The list syncs to your Mixtape account.
 */
class SubscriptionRepository(
    private val context: Context,
    private val dao: SubscriptionDao,
    private val http: OkHttpClient,
) {
    private val feedLock = Mutex()
    private var feedCache: List<FeedVideo> = emptyList()
    private var feedAt = 0L

    fun subscriptions(): Flow<List<SubscriptionEntity>> = dao.active()
    fun subscribedIds(): Flow<Set<String>> = dao.activeIds().map { it.toSet() }

    suspend fun subscribe(c: Channel) {
        dao.upsert(SubscriptionEntity(c.id, c.name, c.avatarUrl, System.currentTimeMillis(), deleted = false))
        feedAt = 0
    }

    suspend fun unsubscribe(channelId: String) {
        val existing = dao.get(channelId) ?: return
        dao.upsert(existing.copy(deleted = true, subscribedAt = System.currentTimeMillis()))
        feedCache = feedCache.filter { it.channelId != channelId }
    }

    fun peekFeed(): List<FeedVideo> = feedCache

    /** Latest uploads from every subscribed channel, newest first. Cached for 20 minutes. */
    suspend fun feed(force: Boolean): List<FeedVideo> = feedLock.withLock {
        if (!force && feedCache.isNotEmpty() && System.currentTimeMillis() - feedAt < 20 * 60 * 1000L) {
            return@withLock feedCache
        }
        val subs = dao.activeOnce()
        val gate = Semaphore(4)
        val all = coroutineScope {
            subs.map { s -> async { gate.withPermit { runCatching { channelVideos(s.channelId, s.name) }.getOrDefault(emptyList()) } } }
                .awaitAll().flatten()
        }
        feedCache = all.sortedByDescending { it.publishedAt }.take(300)
        feedAt = System.currentTimeMillis()
        feedCache
    }

    /** Up to 15 latest uploads of a channel via its RSS feed. */
    suspend fun channelVideos(channelId: String, fallbackName: String = ""): List<FeedVideo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()
        val xml = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Channel feed error ${r.code}")
            r.body?.string().orEmpty()
        }
        parseFeed(xml, channelId, fallbackName)
    }

    private fun parseFeed(xml: String, channelId: String, fallbackName: String): List<FeedVideo> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())
        val out = mutableListOf<FeedVideo>()
        var channelName = fallbackName
        var inEntry = false
        var inAuthor = false
        var videoId: String? = null
        var title: String? = null
        var published: String? = null
        var thumb: String? = null
        var text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "entry" -> { inEntry = true; videoId = null; title = null; published = null; thumb = null }
                        "author" -> inAuthor = true
                        "media:thumbnail" -> if (inEntry) thumb = parser.getAttributeValue(null, "url")
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (parser.name) {
                        "yt:videoId" -> if (inEntry) videoId = t
                        "title" -> if (inEntry) title = t
                        "published" -> if (inEntry) published = t
                        "name" -> if (inAuthor && !inEntry && t.isNotEmpty()) channelName = t
                        "author" -> inAuthor = false
                        "entry" -> {
                            inEntry = false
                            val id = videoId
                            if (id != null && title != null) {
                                val at = runCatching { OffsetDateTime.parse(published).toInstant().toEpochMilli() }.getOrDefault(0L)
                                out += FeedVideo(
                                    track = Track(
                                        source = SourceType.YOUTUBE, sourceId = id, title = title!!,
                                        artist = channelName, artworkUrl = thumb ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                                    ),
                                    channelId = channelId, channelName = channelName, publishedAt = at,
                                )
                            }
                        }
                    }
                    text = StringBuilder()
                }
            }
            event = parser.next()
        }
        // Shorts and normal uploads come mixed; keep the channel name consistent on every item.
        return out.map { it.copy(channelName = channelName, track = it.track.copy(artist = channelName)) }
    }

    /** Channel details for a channel URL (also turns @handle links into a UC... id). */
    suspend fun channelInfo(url: String): Channel = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(ServiceList.YouTube, url)
        Channel(id = info.id, name = info.name, avatarUrl = info.avatars.bestUrl(), subscribers = info.subscriberCount)
    }

    suspend fun searchChannels(query: String): List<Channel> = withContext(Dispatchers.IO) {
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.CHANNELS), "")
        ex.fetchPage()
        ex.initialPage.items.filterIsInstance<ChannelInfoItem>().mapNotNull { c ->
            val id = channelIdFrom(c.url ?: return@mapNotNull null) ?: return@mapNotNull null
            Channel(id, c.name ?: "", c.thumbnails.bestUrl(), c.subscriberCount)
        }
    }

    /**
     * Imports subscriptions from Google Takeout (YouTube and YouTube Music > subscriptions >
     * subscriptions.csv) or a NewPipe export (.json). Returns how many channels were added.
     */
    suspend fun importFile(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Couldn't open the file")
        val channels = if (text.trimStart().startsWith("{")) parseNewPipe(text) else parseTakeout(text)
        if (channels.isEmpty()) throw IOException("No channels found. Use subscriptions.csv from Google Takeout or a NewPipe export.")
        val now = System.currentTimeMillis()
        val existing = dao.allOnce().associateBy { it.channelId }
        val fresh = channels.filter { existing[it.id]?.deleted != false }
        dao.upsertAll(fresh.map { SubscriptionEntity(it.id, it.name, existing[it.id]?.avatarUrl, now, deleted = false) })
        feedAt = 0
        fresh.size
    }

    private fun parseTakeout(text: String): List<Channel> =
        text.removePrefix("﻿").lines().drop(1).mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 3) return@mapNotNull null
            val id = channelIdFrom(parts[0].trim()) ?: channelIdFrom(parts[1].trim()) ?: return@mapNotNull null
            Channel(id, parts.drop(2).joinToString(",").trim().trim('"'), null)
        }.distinctBy { it.id }

    private fun parseNewPipe(text: String): List<Channel> {
        val arr = JSONObject(text).optJSONArray("subscriptions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.optInt("service_id", 0) != 0) return@mapNotNull null
            val id = channelIdFrom(o.optString("url")) ?: return@mapNotNull null
            Channel(id, o.optString("name"), null)
        }.distinctBy { it.id }
    }

    /** Fills in missing channel pictures, a few at a time. */
    suspend fun fillAvatars(limit: Int = 8) {
        val missing = dao.activeOnce().filter { it.avatarUrl == null }.take(limit)
        for (s in missing) {
            runCatching { channelInfo("https://www.youtube.com/channel/${s.channelId}") }.onSuccess { c ->
                dao.upsert(s.copy(avatarUrl = c.avatarUrl, name = c.name.ifBlank { s.name }))
            }
        }
    }

    companion object {
        private val ID = Regex("(UC[\\w-]{22})")
        fun channelIdFrom(s: String): String? = ID.find(s)?.groupValues?.get(1)
    }
}
