package app.mixtape.source

import app.mixtape.core.model.CollectionDetail
import app.mixtape.core.model.RemoteCollection
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.net.BROWSER_USER_AGENT
import app.mixtape.spotify.SpotifyApi
import app.mixtape.spotify.SpotifyAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface LinkResult {
    data class Single(val track: Track) : LinkResult
    data class Collection(val detail: CollectionDetail) : LinkResult
}

/**
 * Turns a pasted or shared link into something playable:
 *  - YouTube / YouTube Music videos and playlists (incl. albums), youtu.be, Shorts
 *  - SoundCloud tracks and sets (incl. on.soundcloud.com short links)
 *  - Spotify tracks, playlists and albums (API when logged in, public embed page otherwise)
 *  - Deezer tracks, playlists and albums (public API, incl. link.deezer.com short links)
 * Also loads playlists picked from search results.
 */
class LinkResolver(
    private val http: OkHttpClient,
    private val spotify: SpotifyApi,
    private val spotifyAuth: SpotifyAuth,
) {
    private val deezer = DeezerApi(http)
    private val cache = ConcurrentHashMap<String, CollectionDetail>()

    fun cached(url: String): CollectionDetail? = cache[url]

    /** Opens Mixtape's own mixes (Daily Mix, Discover Weekly...). Set by the app container. */
    var madeForYou: (suspend (String) -> CollectionDetail?)? = null

    companion object {
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+")
        private val SPOTIFY_PATH = Regex("/(?:intl-[a-z]{2}(?:-[A-Za-z]{2})?/)?(track|playlist|album)/([A-Za-z0-9]{22})")

        /** Finds the first link in text such as "Check this out https://youtu.be/abc via YouTube". */
        fun extractUrl(text: String): String? =
            URL_REGEX.find(text.trim())?.value?.trimEnd(')', '.', ',', '!', '?', ']')

        fun looksLikeLink(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("http://") || t.startsWith("https://") ||
                Regex("^(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be|soundcloud\\.com|open\\.spotify\\.com|on\\.soundcloud\\.com|spotify\\.link|deezer\\.com|link\\.deezer\\.com|deezer\\.page\\.link)/").containsMatchIn(t)
        }
    }

    suspend fun resolve(text: String): LinkResult {
        val raw = extractUrl(text) ?: extractUrl("https://" + text.trim())
            ?: throw IOException("No link found")
        var url = raw
        val host0 = url.toHttpUrlOrNull()?.host ?: throw IOException("That link isn't valid")
        if (host0 == "on.soundcloud.com" || host0.endsWith("spotify.link") || host0 == "spotify.app.link" ||
            host0 == "link.deezer.com" || host0 == "deezer.page.link"
        ) {
            url = followRedirects(url)
        }
        val host = url.toHttpUrlOrNull()?.host ?: throw IOException("That link isn't valid")

        return when {
            host.endsWith("spotify.com") -> resolveSpotify(url)
            host.endsWith("deezer.com") -> resolveDeezer(url)
            host.endsWith("soundcloud.com") -> resolveNewPipe(ServiceList.SoundCloud, url)
            host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com") ->
                resolveNewPipe(ServiceList.YouTube, normalizeYoutube(url))
            else -> throw IOException("Links from $host aren't supported. Try YouTube, SoundCloud or Spotify.")
        }.also { if (it is LinkResult.Collection) cache[it.detail.info.url] = it.detail }
    }

    /** Loads a playlist or album URL from search results (or re-opens one from a link). */
    suspend fun loadCollection(url: String): CollectionDetail {
        if (url.startsWith(MadeForYou.SCHEME)) {
            return madeForYou?.invoke(url.removePrefix(MadeForYou.SCHEME))
                ?: throw IOException("This mix isn't ready yet. Play a few songs and check Home again.")
        }
        cache[url]?.let { return it }
        // YouTube Mixes (list=RD...) look like a song link; open them as the playlist they are.
        val host = url.toHttpUrlOrNull()?.host.orEmpty()
        if (host.endsWith("youtube.com") && url.toHttpUrlOrNull()?.queryParameter("list") != null) {
            val d = NewPipeCollections.loadPlaylist(ServiceList.YouTube, url)
            return d.copy(info = d.info.copy(url = url)).also { cache[url] = it }
        }
        return when (val r = resolve(url)) {
            is LinkResult.Collection -> r.detail
            is LinkResult.Single -> throw IOException("That link is a single song, not a playlist")
        }
    }

    suspend fun refreshCollection(url: String): CollectionDetail {
        cache.remove(url)
        return loadCollection(url)
    }

    /** watch?v=X&list=PL... means "this playlist"; radio/mix lists (RD...) are treated as the song. */
    private fun normalizeYoutube(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        val list = u.queryParameter("list")
        if (list != null && !list.startsWith("RD") && u.encodedPath.startsWith("/watch")) {
            val host = if (u.host.startsWith("music.")) "music.youtube.com" else "www.youtube.com"
            return "https://$host/playlist?list=$list"
        }
        return url
    }

    private suspend fun resolveNewPipe(service: StreamingService, url: String): LinkResult {
        val type = withContext(Dispatchers.IO) { service.getLinkTypeByUrl(url) }
        return when (type) {
            StreamingService.LinkType.STREAM -> LinkResult.Single(NewPipeCollections.loadSingle(service, url))
            StreamingService.LinkType.PLAYLIST -> {
                val d = NewPipeCollections.loadPlaylist(service, url)
                LinkResult.Collection(d.copy(info = d.info.copy(url = url)))
            }
            StreamingService.LinkType.CHANNEL -> throw IOException("Channel links aren't supported yet. Paste a song or playlist link.")
            else -> throw IOException("Couldn't read that link")
        }
    }

    // ---------------------------------------------------------------- Deezer

    private suspend fun resolveDeezer(url: String): LinkResult {
        val m = Regex("/(?:[a-z]{2}(?:-[a-z]{2})?/)?(track|playlist|album)/(\\d+)").find(url.toHttpUrlOrNull()?.encodedPath ?: "")
            ?: throw IOException("Only Deezer song, playlist and album links are supported")
        val id = m.groupValues[2]
        return when (m.groupValues[1]) {
            "track" -> LinkResult.Single(deezer.track(id))
            "album" -> LinkResult.Collection(deezer.album(id))
            else -> LinkResult.Collection(deezer.playlist(id))
        }
    }

    // ---------------------------------------------------------------- Spotify

    private suspend fun resolveSpotify(url: String): LinkResult {
        val m = SPOTIFY_PATH.find(url.toHttpUrlOrNull()?.encodedPath ?: "")
            ?: throw IOException("Only Spotify song, playlist and album links are supported")
        val kind = m.groupValues[1]
        val id = m.groupValues[2]
        val canonical = "https://open.spotify.com/$kind/$id"

        // Prefer the official API when logged in; fall back to the public embed page, which also
        // works for playlists you don't own and without any Spotify account.
        if (spotifyAuth.loggedIn.value) {
            val viaApi = runCatching {
                when (kind) {
                    "track" -> LinkResult.Single(spotify.track(id))
                    "album" -> {
                        val (info, tracks) = spotify.album(id)
                        LinkResult.Collection(
                            CollectionDetail(
                                RemoteCollection(canonical, info.name, listOf(info.owner, "Spotify album").filter { it.isNotBlank() }.joinToString(" · "), info.imageUrl, tracks.size.toLong()),
                                tracks,
                            )
                        )
                    }
                    else -> {
                        val info = spotify.playlistInfo(id)
                        val tracks = spotify.playlistTracks(id)
                        LinkResult.Collection(
                            CollectionDetail(
                                RemoteCollection(canonical, info.name, listOf(info.owner, "Spotify playlist").filter { it.isNotBlank() }.joinToString(" · "), info.imageUrl, tracks.size.toLong()),
                                tracks,
                            )
                        )
                    }
                }
            }.getOrNull()
            if (viaApi != null) return viaApi
        }
        return resolveSpotifyEmbed(kind, id, canonical)
    }

    private suspend fun resolveSpotifyEmbed(kind: String, id: String, canonical: String): LinkResult =
        withContext(Dispatchers.IO) {
            val html = fetch("https://open.spotify.com/embed/$kind/$id")
            val json = Regex("<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
                ?: throw IOException("Spotify didn't return the playlist. Log in to Spotify in the app and try again.")
            val root = JSONObject(json)
            val entity = findEntity(root) ?: throw IOException("Couldn't read that Spotify link")

            val cover = coverUrl(entity)
            if (kind == "track") {
                val track = embedTrack(entity, id, cover) ?: throw IOException("Couldn't read that Spotify song")
                return@withContext LinkResult.Single(track)
            }
            val list = entity.optJSONArray("trackList") ?: JSONArray()
            val tracks = (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val trackId = t.optString("uri").substringAfter("spotify:track:", "").takeIf { it.length == 22 }
                    ?: return@mapNotNull null
                Track(
                    source = SourceType.SPOTIFY,
                    sourceId = trackId,
                    title = t.optString("title").ifEmpty { t.optString("name") },
                    artist = t.optString("subtitle").replace(" ", " ").replace(Regex("\\s*,\\s*"), ", "),
                    album = if (kind == "album") entity.optString("name").ifEmpty { null } else null,
                    durationSec = t.optLong("duration", 0) / 1000,
                    artworkUrl = cover,
                )
            }
            if (tracks.isEmpty()) throw IOException("That Spotify $kind looks empty or private")
            val title = entity.optString("name").ifEmpty { entity.optString("title", "Spotify $kind") }
            val owner = entity.optString("subtitle").ifEmpty { entity.optJSONObject("owner")?.optString("name").orEmpty() }
            LinkResult.Collection(
                CollectionDetail(
                    RemoteCollection(
                        canonical, title,
                        listOf(owner, "Spotify $kind").filter { it.isNotBlank() }.joinToString(" · "),
                        cover, tracks.size.toLong(),
                    ),
                    tracks,
                )
            )
        }

    /** The embed page's JSON layout shifts over time, so search for the object that holds the data. */
    private fun findEntity(root: JSONObject): JSONObject? {
        root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity")?.let { return it }
        return deepFind(root, 0) { o ->
            o.has("trackList") || (o.optString("type") == "track" && o.has("uri") && o.has("name"))
        }
    }

    private fun deepFind(node: Any?, depth: Int, test: (JSONObject) -> Boolean): JSONObject? {
        if (depth > 12) return null
        when (node) {
            is JSONObject -> {
                if (test(node)) return node
                val keys = node.keys()
                while (keys.hasNext()) deepFind(node.opt(keys.next()), depth + 1, test)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) deepFind(node.opt(i), depth + 1, test)?.let { return it }
        }
        return null
    }

    private fun coverUrl(entity: JSONObject): String? {
        entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        entity.optJSONObject("visualIdentity")?.optJSONArray("image")?.let { arr ->
            var best: String? = null
            var bestW = -1
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optInt("maxWidth", 0)
                if (w > bestW) { bestW = w; best = o.optString("url") }
            }
            if (!best.isNullOrEmpty()) return best
        }
        return entity.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotEmpty() }
    }

    private fun embedTrack(entity: JSONObject, id: String, cover: String?): Track? {
        val title = entity.optString("name").ifEmpty { entity.optString("title") }.ifEmpty { return null }
        val artists = entity.optJSONArray("artists")
        val artist = if (artists != null && artists.length() > 0) {
            (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotEmpty() } }
                .joinToString(", ")
        } else entity.optString("subtitle")
        return Track(
            source = SourceType.SPOTIFY,
            sourceId = id,
            title = title,
            artist = artist,
            durationSec = entity.optLong("duration", 0) / 1000,
            artworkUrl = cover,
        )
    }

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept-Language", "en")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Couldn't open link (HTTP ${r.code})")
            return r.body?.string().orEmpty()
        }
    }

    private suspend fun followRedirects(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            val finalUrl = r.request.url.toString()
            if (finalUrl.contains("open.spotify.com") || finalUrl.contains("soundcloud.com/")) {
                if (!finalUrl.contains("on.soundcloud.com")) return@withContext finalUrl
            }
            // spotify.link lands on an HTML page that redirects with JavaScript; dig the target out.
            val body = r.body?.string().orEmpty()
            Regex("https://open\\.spotify\\.com/(?:intl-[a-z-]+/)?(?:track|playlist|album)/[A-Za-z0-9]{22}")
                .find(body)?.value
                ?: Regex("https://soundcloud\\.com/[^\"'\\s<>?]+").find(body)?.value
                ?: finalUrl
        }
    }
}
