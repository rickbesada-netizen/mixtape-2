package app.mixtape.source

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** The Google account you signed in to YouTube with. */
data class YouTubeUser(val name: String, val handle: String?, val photo: String?)

/**
 * Your own YouTube account inside Mixtape: sign in once (on Google's own sign-in page) and Mixtape
 * shows your real recommendations, subscriptions feed, Watch later, liked videos and history, and
 * subscribing / liking here also happens on YouTube. It talks to YouTube the way youtube.com does,
 * with the sign-in cookies kept only on this phone.
 */
class YouTubeAccount(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("youtube_account", Context.MODE_PRIVATE)

    private val _cookies = MutableStateFlow(prefs.getString("cookies", null))
    private val _user = MutableStateFlow(
        prefs.getString("name", null)?.let { YouTubeUser(it, prefs.getString("handle", null), prefs.getString("photo", null)) }
    )
    val user: StateFlow<YouTubeUser?> = _user.asStateFlow()

    private val _signedIn = MutableStateFlow(_cookies.value != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    // ---------------------------------------------------------------- signing in / out

    /** Called by the sign-in page: true once Google has signed you in to YouTube. */
    fun captureCookies(): Boolean {
        val cm = CookieManager.getInstance()
        val raw = cm.getCookie(ORIGIN) ?: return false
        if (sapisid(raw) == null) return false
        prefs.edit().putString("cookies", raw).apply()
        _cookies.value = raw
        _signedIn.value = true
        return true
    }

    /**
     * Backup way in: the cookie text from youtube.com in a computer's browser, where you're
     * already signed in. Checked against YouTube before it's kept.
     */
    suspend fun signInWithCookies(raw: String): Boolean {
        val cookies = raw.trim().removePrefix("Cookie:").trim().replace("\n", " ")
        if (sapisid(cookies) == null) return false
        val before = _cookies.value
        _cookies.value = cookies
        val ok = runCatching { find(call("account/account_menu", JSONObject()), "activeAccountHeaderRenderer") != null }.getOrDefault(false)
        if (!ok) { _cookies.value = before; return false }
        prefs.edit().putString("cookies", cookies).apply()
        _signedIn.value = true
        refreshUser()
        return true
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _cookies.value = null
        _user.value = null
        _signedIn.value = false
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** Your name and picture, for the You page. */
    suspend fun refreshUser(): YouTubeUser? {
        val j = runCatching { call("account/account_menu", JSONObject()) }.getOrNull() ?: return _user.value
        val header = find(j, "activeAccountHeaderRenderer") ?: return _user.value
        val u = YouTubeUser(
            name = text(header.opt("accountName")) ?: "You",
            handle = text(header.opt("channelHandle")),
            photo = header.optJSONObject("accountPhoto")?.let { thumb(it) },
        )
        prefs.edit().putString("name", u.name).putString("handle", u.handle).putString("photo", u.photo).apply()
        _user.value = u
        return u
    }

    // ---------------------------------------------------------------- lists

    /** Home: what YouTube recommends for you. */
    fun recommended(): Pages<YtItem> = BrowsePages("FEwhat_to_watch")

    /** Newest videos from the channels you're subscribed to. */
    fun subscriptionsFeed(): Pages<YtItem> = BrowsePages("FEsubscriptions")

    fun history(): Pages<YtItem> = BrowsePages("FEhistory")
    fun watchLater(): Pages<YtItem> = BrowsePages("VLWL")
    fun likedVideos(): Pages<YtItem> = BrowsePages("VLLL")

    /** Every channel you're subscribed to on YouTube. */
    suspend fun subscribedChannels(): List<Channel> {
        val out = LinkedHashMap<String, Channel>()
        var j = call("browse", JSONObject().put("browseId", "FEchannels"))
        repeat(40) {
            walk(j) { key, o ->
                if (key == "channelRenderer") {
                    val id = o.optString("channelId")
                    if (id.startsWith("UC")) out[id] = Channel(id, text(o.opt("title")) ?: "", o.optJSONObject("thumbnail")?.let { thumb(it) })
                }
            }
            val token = continuation(j) ?: return out.values.toList()
            j = call("browse", JSONObject().put("continuation", token))
        }
        return out.values.toList()
    }

    // ---------------------------------------------------------------- actions

    suspend fun subscribe(channelId: String) {
        call("subscription/subscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))
    }

    suspend fun unsubscribe(channelId: String) {
        call("subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))
    }

    suspend fun like(videoId: String, liked: Boolean) {
        call(if (liked) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    suspend fun dislike(videoId: String) {
        call("like/dislike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    // ---------------------------------------------------------------- talking to YouTube

    private inner class BrowsePages(private val browseId: String) : Pages<YtItem> {
        private var token: String? = null
        private var started = false
        override var done = false
            private set

        override suspend fun load(): List<YtItem> {
            if (done) return emptyList()
            val body = if (!started) JSONObject().put("browseId", browseId)
            else JSONObject().put("continuation", token ?: run { done = true; return emptyList() })
            val j = call("browse", body)
            started = true
            token = continuation(j)
            if (token == null) done = true
            return videos(j).map { YtItem.Video(it) }
        }
    }

    private suspend fun call(endpoint: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val cookies = _cookies.value ?: throw IOException("Sign in to YouTube first")
        val sapisid = sapisid(cookies) ?: throw IOException("Sign in to YouTube again")
        body.put(
            "context", JSONObject().put(
                "client", JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", CLIENT_VERSION)
                    .put("hl", java.util.Locale.getDefault().language.ifBlank { "en" })
                    .put("gl", java.util.Locale.getDefault().country.ifBlank { "US" })
            )
        )
        val req = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Cookie", cookies)
            .header("Authorization", authHeader(sapisid))
            .header("X-Origin", ORIGIN)
            .header("Origin", ORIGIN)
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) {
                throw IOException("YouTube signed you out. Sign in again under You.")
            }
            if (!r.isSuccessful) throw IOException("YouTube said ${r.code}")
            JSONObject(text)
        }
    }

    private fun authHeader(sapisid: String): String {
        val ts = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hash"
    }

    companion object {
        const val ORIGIN = "https://www.youtube.com"
        private const val CLIENT_VERSION = "2.20241126.01.00"

        /** A normal desktop Chrome, so Google shows its usual sign-in page. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** Google's sign-in page, which comes back to YouTube once you're in. */
        const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"

        /** The phone's own Chrome identity, minus the parts that mark it as an in-app browser. */
        fun chromeUserAgent(default: String): String =
            default.replace("; wv", "").replace(Regex("Version/[\\d.]+ "), "")

        fun sapisid(cookies: String): String? = cookies.split(";").map { it.trim() }.let { parts ->
            fun get(name: String) = parts.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
            get("SAPISID") ?: get("__Secure-3PAPISID")
        }

        // ---- reading YouTube's answers (they change often, so this looks for the parts wherever they are)

        fun walk(node: Any?, visit: (String, JSONObject) -> Unit) {
            when (node) {
                is JSONObject -> for (k in node.keys()) {
                    val v = node.opt(k)
                    if (v is JSONObject) visit(k, v)
                    walk(v, visit)
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
            }
        }

        fun find(node: Any?, key: String): JSONObject? {
            var found: JSONObject? = null
            walk(node) { k, o -> if (found == null && k == key) found = o }
            return found
        }

        /** The "load more" token at the end of a page. */
        fun continuation(j: JSONObject): String? {
            var token: String? = null
            walk(j) { k, o ->
                if (k == "continuationCommand" && token == null) token = o.optString("token").takeIf { it.isNotBlank() }
                if (k == "nextContinuationData" && token == null) token = o.optString("continuation").takeIf { it.isNotBlank() }
            }
            return token
        }

        fun text(o: Any?): String? = when (o) {
            is JSONObject -> o.optString("simpleText").takeIf { it.isNotBlank() }
                ?: o.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }
                    ?.takeIf { it.isNotBlank() }
                ?: o.optString("content").takeIf { it.isNotBlank() }
            is String -> o.takeIf { it.isNotBlank() }
            else -> null
        }

        fun thumb(o: JSONObject): String? {
            val arr = o.optJSONArray("thumbnails") ?: o.optJSONArray("sources") ?: return null
            val url = arr.optJSONObject(arr.length() - 1)?.optString("url") ?: return null
            return if (url.startsWith("//")) "https:$url" else url
        }

        private fun firstThumb(o: Any?): String? {
            var url: String? = null
            walk(o) { k, v -> if (url == null && (k == "thumbnail" || k == "image")) url = thumb(v) }
            return url
        }

        private fun firstChannelId(o: Any?): String? {
            var id: String? = null
            walk(o) { k, v -> if (id == null && k == "browseEndpoint") v.optString("browseId").takeIf { it.startsWith("UC") }?.let { id = it } }
            return id
        }

        /** Every video on a page, in either of the two layouts YouTube uses. */
        fun videos(j: JSONObject): List<VideoItem> {
            val out = LinkedHashMap<String, VideoItem>()
            walk(j) { k, o ->
                when (k) {
                    "videoRenderer", "gridVideoRenderer", "playlistVideoRenderer" -> fromRenderer(o)?.let { out.putIfAbsent(it.id, it) }
                    "lockupViewModel" -> fromLockup(o)?.let { out.putIfAbsent(it.id, it) }
                }
            }
            return out.values.toList()
        }

        private fun fromRenderer(o: JSONObject): VideoItem? {
            val id = o.optString("videoId").takeIf { it.isNotBlank() } ?: return null
            val owner = o.optJSONObject("ownerText") ?: o.optJSONObject("shortBylineText") ?: o.optJSONObject("longBylineText")
            val channelId = firstChannelId(owner)
            val avatar = o.optJSONObject("channelThumbnailSupportedRenderers")?.let { firstThumb(it) }
                ?: o.optJSONObject("channelThumbnail")?.let { thumb(it) }
            val badges = o.optJSONArray("badges")?.toString().orEmpty() + o.optJSONArray("thumbnailOverlays")?.toString().orEmpty()
            val length = text(o.opt("lengthText"))
                ?: find(o.optJSONArray("thumbnailOverlays"), "thumbnailOverlayTimeStatusRenderer")?.let { text(it.opt("text")) }
            return VideoItem(
                id = id,
                title = text(o.opt("title")) ?: return null,
                channel = text(owner) ?: "",
                channelUrl = channelId?.let { "https://www.youtube.com/channel/$it" },
                thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                durationSec = parseClock(length),
                views = parseViews(text(o.opt("viewCountText")) ?: text(o.opt("shortViewCountText"))),
                ago = text(o.opt("publishedTimeText")) ?: "",
                avatar = avatar,
                isLive = badges.contains("LIVE"),
                isShort = badges.contains("SHORTS"),
            )
        }

        private fun fromLockup(o: JSONObject): VideoItem? {
            val type = o.optString("contentType")
            if (type.isNotBlank() && type != "LOCKUP_CONTENT_TYPE_VIDEO") return null
            val id = o.optString("contentId").takeIf { it.isNotBlank() } ?: return null
            val meta = find(o, "lockupMetadataViewModel") ?: return null
            val title = text(meta.opt("title")) ?: return null
            val rows = find(meta, "contentMetadataViewModel")?.optJSONArray("metadataRows")
            val parts = (0 until (rows?.length() ?: 0)).map { i ->
                val p = rows!!.optJSONObject(i)?.optJSONArray("metadataParts")
                (0 until (p?.length() ?: 0)).mapNotNull { text(p!!.optJSONObject(it)?.opt("text")) }
            }
            val channel = parts.getOrNull(0)?.firstOrNull() ?: ""
            val second = parts.getOrNull(1).orEmpty()
            var duration: String? = null
            var live = false
            walk(o.optJSONObject("contentImage")) { k, v ->
                if (k == "thumbnailBadgeViewModel") {
                    val t = v.optString("text")
                    if (t.matches(Regex("\\d+(:\\d{2})+"))) duration = t
                    if (t.contains("LIVE", ignoreCase = true)) live = true
                }
            }
            val avatarNode = find(meta, "avatarViewModel")
            return VideoItem(
                id = id,
                title = title,
                channel = channel,
                channelUrl = firstChannelId(meta)?.let { "https://www.youtube.com/channel/$it" },
                thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                durationSec = parseClock(duration),
                views = parseViews(second.firstOrNull { it.contains("view", ignoreCase = true) || it.contains("watching", ignoreCase = true) }),
                ago = second.firstOrNull { it.contains("ago") || it.contains("Streamed") } ?: "",
                avatar = avatarNode?.let { firstThumb(it) },
                isLive = live,
            )
        }

        fun parseClock(s: String?): Long {
            if (s.isNullOrBlank()) return 0
            return s.trim().split(":").mapNotNull { it.trim().toLongOrNull() }.fold(0L) { acc, n -> acc * 60 + n }
        }

        /** "1,234 views", "1.2M views", "15K watching" → a number. */
        fun parseViews(s: String?): Long {
            if (s.isNullOrBlank()) return -1
            val m = Regex("([\\d.,]+)\\s*([KMB])?", RegexOption.IGNORE_CASE).find(s) ?: return -1
            val n = m.groupValues[1].replace(",", "")
            val mult = when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
            return ((n.toDoubleOrNull() ?: return -1) * mult).toLong()
        }
    }
}
