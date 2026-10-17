package app.mixtape.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

const val SPOTIFY_REDIRECT_URI = "mixtape-auth://callback"
private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"

/**
 * Authorization Code with PKCE. No client secret is ever shipped: each user registers their own
 * app at developer.spotify.com and pastes its Client ID here (Development Mode allows 5 users per
 * app and requires the app owner to have Premium, so a shared ID would not scale anyway).
 */
class SpotifyAuth(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()

    private val _loggedIn = MutableStateFlow(prefs.getString("refresh_token", null) != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _clientId = MutableStateFlow(prefs.getString("client_id", "") ?: "")
    val clientId: StateFlow<String> = _clientId.asStateFlow()

    fun setClientId(id: String) {
        prefs.edit().putString("client_id", id.trim()).apply()
        _clientId.value = id.trim()
    }

    fun buildAuthorizeUri(): Uri {
        val clientId = _clientId.value
        require(clientId.isNotBlank()) { "Enter your Spotify Client ID first" }
        val verifier = randomString(64)
        val state = randomString(16)
        prefs.edit().putString("pkce_verifier", verifier).putString("pkce_state", state).apply()
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()
    }

    /** Called with the mixtape-auth://callback?code=... redirect. */
    suspend fun handleRedirect(uri: Uri) = withContext(Dispatchers.IO) {
        uri.getQueryParameter("error")?.let { throw IOException("Spotify login failed: $it") }
        val code = uri.getQueryParameter("code") ?: throw IOException("No authorization code returned")
        val expectedState = prefs.getString("pkce_state", null)
        if (expectedState != null && uri.getQueryParameter("state") != expectedState) {
            throw IOException("Login state mismatch, please try again")
        }
        val verifier = prefs.getString("pkce_verifier", null) ?: throw IOException("Login session expired")
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", SPOTIFY_REDIRECT_URI)
            .add("client_id", _clientId.value)
            .add("code_verifier", verifier)
            .build()
        storeTokens(postToken(form))
        prefs.edit().remove("pkce_verifier").remove("pkce_state").apply()
    }

    suspend fun accessToken(): String = refreshLock.withLock {
        val token = prefs.getString("access_token", null)
        val expiresAt = prefs.getLong("expires_at", 0)
        if (token != null && System.currentTimeMillis() < expiresAt - 60_000) return@withLock token
        val refresh = prefs.getString("refresh_token", null) ?: throw IOException("Not logged in to Spotify")
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", _clientId.value)
                .build()
            storeTokens(postToken(form))
        }
        prefs.getString("access_token", null)!!
    }

    fun logout() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
        _loggedIn.value = false
    }

    private fun postToken(form: FormBody): JSONObject {
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(form).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (r.code == 400 && body.contains("invalid_grant")) logout()
                throw IOException("Spotify token error ${r.code}: ${body.take(200)}")
            }
            return JSONObject(body)
        }
    }

    private fun storeTokens(json: JSONObject) {
        val e = prefs.edit()
            .putString("access_token", json.getString("access_token"))
            .putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
        // Spotify may rotate the refresh token; keep the old one if none is returned.
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { e.putString("refresh_token", it) }
        e.apply()
        _loggedIn.value = true
    }

    private fun randomString(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return (1..len).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val trackCount: Int,
    val imageUrl: String?,
)

class SpotifyApi(private val auth: SpotifyAuth, private val http: OkHttpClient) {

    suspend fun myPlaylists(): List<SpotifyPlaylist> {
        val out = mutableListOf<SpotifyPlaylist>()
        var url: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        while (url != null) {
            val page = get(url)
            val items = page.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                // Feb 2026 API: "tracks" summary renamed to "items"; support both.
                val summary = p.optJSONObject("items") ?: p.optJSONObject("tracks")
                out += SpotifyPlaylist(
                    id = p.getString("id"),
                    name = p.optString("name"),
                    owner = p.optJSONObject("owner")?.optString("display_name").orEmpty(),
                    trackCount = summary?.optInt("total") ?: 0,
                    imageUrl = p.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
                )
            }
            url = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    suspend fun playlistInfo(id: String): SpotifyPlaylist {
        val p = get("https://api.spotify.com/v1/playlists/$id?fields=id,name,owner(display_name),images")
        return SpotifyPlaylist(
            id = p.optString("id", id),
            name = p.optString("name", "Spotify playlist"),
            owner = p.optJSONObject("owner")?.optString("display_name").orEmpty(),
            trackCount = 0,
            imageUrl = p.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
        )
    }

    /** Only works for playlists you own or collaborate on (Spotify API rule since Feb 2026). */
    suspend fun playlistTracks(id: String): List<Track> {
        return try {
            pagedTracks("https://api.spotify.com/v1/playlists/$id/items?limit=50")
        } catch (e: SpotifyHttpException) {
            if (e.code == 404) pagedTracks("https://api.spotify.com/v1/playlists/$id/tracks?limit=50")
            else if (e.code == 403) throw IOException(
                "Spotify only allows reading playlists you own or collaborate on. " +
                    "Copy it into your own library first, or import a CSV export."
            ) else throw e
        }
    }

    suspend fun savedTracks(): List<Track> = pagedTracks("https://api.spotify.com/v1/me/tracks?limit=50")

    suspend fun track(id: String): Track =
        parseTrack(get("https://api.spotify.com/v1/tracks/$id")) ?: throw IOException("Spotify track not available")

    /** Album info plus its tracks (album tracks come without album details, so we add them). */
    suspend fun album(id: String): Pair<SpotifyPlaylist, List<Track>> {
        val a = get("https://api.spotify.com/v1/albums/$id")
        val info = SpotifyPlaylist(
            id = id,
            name = a.optString("name", "Album"),
            owner = a.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty(),
            trackCount = a.optInt("total_tracks"),
            imageUrl = a.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
        )
        val albumStub = JSONObject().put("name", info.name).put("images", a.optJSONArray("images"))
        val out = mutableListOf<Track>()
        var page: JSONObject? = a.optJSONObject("tracks")
        while (page != null) {
            val items = page.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val t = items.optJSONObject(i) ?: continue
                if (t.optJSONObject("album") == null) t.put("album", albumStub)
                parseTrack(t)?.let { out += it }
            }
            val next = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
            page = next?.let { get(it) }
        }
        return info to out
    }

    private suspend fun pagedTracks(first: String): List<Track> {
        val out = mutableListOf<Track>()
        var url: String? = first
        while (url != null) {
            val page = get(url)
            val items = page.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val t = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue
                parseTrack(t)?.let { out += it }
            }
            url = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    private fun parseTrack(t: JSONObject): Track? {
        if (t.optString("type", "track") != "track") return null
        if (t.optBoolean("is_local", false)) return null
        val id = t.optString("id").takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val artists = t.optJSONArray("artists")
        val artistNames = buildList {
            if (artists != null) for (i in 0 until artists.length()) {
                artists.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        val album = t.optJSONObject("album")
        return Track(
            source = SourceType.SPOTIFY,
            sourceId = id,
            title = t.optString("name"),
            artist = artistNames.joinToString(", "),
            album = album?.optString("name"),
            durationSec = t.optLong("duration_ms", 0) / 1000,
            artworkUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
            isrc = t.optJSONObject("external_ids")?.optString("isrc")?.takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val token = auth.accessToken()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 429) {
                val wait = r.header("Retry-After")?.toLongOrNull() ?: 2
                throw IOException("Spotify rate limit hit, try again in ${wait}s")
            }
            if (!r.isSuccessful) throw SpotifyHttpException(r.code, body.take(200))
            JSONObject(body)
        }
    }

    companion object {
        /** Accepts open.spotify.com/playlist/{id}, spotify:playlist:{id} or a bare id. */
        fun parsePlaylistId(input: String): String? {
            val s = input.trim()
            Regex("playlist[/:]([A-Za-z0-9]{22})").find(s)?.let { return it.groupValues[1] }
            return s.takeIf { Regex("^[A-Za-z0-9]{22}$").matches(it) }
        }
    }
}

class SpotifyHttpException(val code: Int, body: String) : IOException("Spotify API error $code: $body")
