package app.mixtape.cloud

import android.content.Context
import app.mixtape.BuildConfig
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.data.LibraryRepository
import app.mixtape.data.db.AppDatabase
import app.mixtape.data.db.DownloadState
import app.mixtape.data.db.MatchEntity
import app.mixtape.data.db.PlaylistEntity
import app.mixtape.data.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

data class CloudAccount(val userId: String, val email: String)

/**
 * Supabase account + library sync, over plain REST (GoTrue for auth, PostgREST for data).
 *
 * What syncs: liked songs, every playlist with its order, Spotify→YouTube match fixes, and the list
 * of songs/videos you downloaded. Audio files themselves are never uploaded: on a new phone,
 * "Restore downloads" downloads them again from YouTube / SoundCloud.
 */
class CloudSync(
    context: Context,
    private val http: OkHttpClient,
    private val db: AppDatabase,
    private val library: LibraryRepository,
) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = "application/json".toMediaType()

    init {
        // The first Supabase project was deleted. Forget a server address saved for it, and any
        // login session that belongs to a different server than the one now in use.
        if (prefs.getString("url", null)?.contains(OLD_PROJECT_REF) == true) {
            prefs.edit().remove("url").remove("anon_key").apply()
        }
        val sessionUrl = prefs.getString("session_url", null)
        val signedInBefore = prefs.getString("refresh_token", null) != null
        if (signedInBefore && (sessionUrl == null || sessionUrl != url)) {
            prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at")
                .remove("user_id").remove("email").remove("status").apply()
        }
    }

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<CloudAccount?> = _account.asStateFlow()

    private val _status = MutableStateFlow(prefs.getString("status", null) ?: "")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Songs/videos downloaded on another device (or before a reinstall) but not on this one. */
    private val _restorable = MutableStateFlow<List<Track>>(emptyList())
    val restorable: StateFlow<List<Track>> = _restorable.asStateFlow()

    // ---------------------------------------------------------------- server settings

    val url: String get() = (prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SUPABASE_URL).trimEnd('/')
    val anonKey: String get() = prefs.getString("anon_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SUPABASE_ANON_KEY
    val isConfigured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank()
    val builtInServer: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank()

    fun setServer(url: String, anonKey: String) {
        prefs.edit().putString("url", url.trim()).putString("anon_key", anonKey.trim()).apply()
    }

    // ---------------------------------------------------------------- auth

    private fun loadAccount(): CloudAccount? {
        val id = prefs.getString("user_id", null) ?: return null
        if (prefs.getString("refresh_token", null) == null) return null
        return CloudAccount(id, prefs.getString("email", "") ?: "")
    }

    /** @return null when logged in, or a message (e.g. "check your email") */
    suspend fun signUp(email: String, password: String): String? = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val res = authPost("/auth/v1/signup", body)
        if (res.has("access_token")) {
            storeSession(res)
            null
        } else {
            "Account created. Check your email to confirm it, then log in."
        }
    }

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().put("email", email.trim()).put("password", password)
        storeSession(authPost("/auth/v1/token?grant_type=password", body))
    }

    fun signOut() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at")
            .remove("user_id").remove("email").remove("status").apply()
        _account.value = null
        _restorable.value = emptyList()
        _status.value = ""
    }

    private fun storeSession(res: JSONObject) {
        val user = res.optJSONObject("user") ?: throw IOException("Login failed: no user returned")
        prefs.edit()
            .putString("access_token", res.getString("access_token"))
            .putString("refresh_token", res.getString("refresh_token"))
            .putLong("expires_at", System.currentTimeMillis() + res.optLong("expires_in", 3600) * 1000)
            .putString("user_id", user.getString("id"))
            .putString("session_url", url)
            .putString("email", user.optString("email"))
            .apply()
        _account.value = CloudAccount(user.getString("id"), user.optString("email"))
    }

    private val tokenLock = Mutex()

    private suspend fun accessToken(): String = tokenLock.withLock {
        val token = prefs.getString("access_token", null)
        if (token != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 60_000) return@withLock token
        val refresh = prefs.getString("refresh_token", null) ?: throw IOException("Not logged in")
        val res = try {
            authPost("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh))
        } catch (e: CloudHttpException) {
            if (e.code == 400 || e.code == 401) {
                signOut()
                throw IOException("Your session expired, please log in again")
            }
            throw e
        }
        storeSession(res)
        res.getString("access_token")
    }

    private fun authPost(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url + path)
            .header("apikey", anonKey)
            .post(body.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching {
                    val o = JSONObject(text)
                    o.optString("msg").ifEmpty { o.optString("error_description") }.ifEmpty { o.optString("message") }
                }.getOrNull()
                throw CloudHttpException(r.code, msg?.takeIf { it.isNotBlank() } ?: "Server error ${r.code}")
            }
            return JSONObject(text)
        }
    }

    private fun requireConfigured() {
        if (!isConfigured) throw IOException("Add your Supabase URL and anon key first (Settings > Account > Server)")
    }

    // ---------------------------------------------------------------- REST helpers

    private suspend fun rest(method: String, pathAndQuery: String, body: Any? = null, prefer: String? = null): String {
        val token = accessToken()
        val b = Request.Builder().url("$url/rest/v1/$pathAndQuery")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
        if (prefer != null) b.header("Prefer", prefer)
        val rb = body?.toString()?.toRequestBody(json)
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete(rb)
            "PATCH" -> b.patch(rb ?: "{}".toRequestBody(json))
            else -> b.post(rb ?: "[]".toRequestBody(json))
        }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val hint = if (r.code == 404 || text.contains("does not exist") || text.contains("PGRST205")) {
                    " (did you run supabase/schema.sql in your project?)"
                } else ""
                throw CloudHttpException(r.code, "Sync error ${r.code}: ${text.take(160)}$hint")
            }
            return text
        }
    }

    private suspend fun getAll(table: String, order: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val arr = JSONArray(rest("GET", "$table?select=*&order=$order&limit=1000&offset=$offset"))
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            if (arr.length() < 1000) break
            offset += 1000
        }
        return out
    }

    private suspend fun upsert(table: String, rows: List<JSONObject>) {
        rows.chunked(500).forEach { chunk ->
            rest("POST", table, JSONArray(chunk), prefer = "resolution=merge-duplicates,return=minimal")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- sync

    fun syncSoon() {
        if (_account.value == null || !isConfigured) return
        scope.launch { runCatching { sync() } }
    }

    /** Pull from the cloud, merge into this phone, then push this phone's library back up. */
    suspend fun sync(): String = mutex.withLock {
        if (_account.value == null) throw IOException("Not logged in")
        _syncing.value = true
        try {
            pushPendingDeletes()
            val pulled = pull()
            val pushed = push()
            val msg = "Synced ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}: " +
                "$pulled updates from the cloud, $pushed playlists uploaded"
            _status.value = msg
            prefs.edit().putString("status", msg).apply()
            msg
        } catch (e: Exception) {
            val msg = "Last sync failed: ${e.message}"
            _status.value = msg
            throw e
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun pull(): Int {
        var changes = 0
        val trackDao = db.tracks()
        val playlistDao = db.playlists()

        // Songs + likes (newest like/unlike wins)
        val remoteTracks = getAll("library_tracks", "track_key")
        val asTracks = remoteTracks.mapNotNull { it.toTrack() }
        library.saveTracks(asTracks)
        for (r in remoteTracks) {
            val key = r.optString("track_key")
            val local = trackDao.get(key) ?: continue
            val remoteAt = r.optLong("liked_at", 0)
            val remoteLiked = r.optBoolean("liked", false)
            if (remoteAt > local.likedAt && remoteLiked != local.liked) {
                trackDao.setLiked(key, remoteLiked, remoteAt)
                changes++
            }
        }

        // What was downloaded elsewhere but isn't here
        val doneHere = db.downloads().doneKeysOnce().toSet()
        _restorable.value = remoteTracks
            .filter { it.optBoolean("downloaded", false) && it.optString("track_key") !in doneHere }
            .mapNotNull { it.toTrack() }

        // Playlists
        val remotePlaylists = getAll("playlists", "sync_id")
        val items = getAll("playlist_items", "playlist_sync_id,position")
            .groupBy({ it.optString("playlist_sync_id") }, { it.optString("track_key") })
        for (p in remotePlaylists) {
            val syncId = p.optString("sync_id")
            val remoteUpdated = p.optLong("updated_at", 0)
            val local = playlistDao.bySyncId(syncId)
            if (p.optBoolean("deleted", false)) {
                if (local != null) {
                    playlistDao.clearEntries(local.id)
                    playlistDao.delete(local.id)
                    changes++
                }
                continue
            }
            val keys = items[syncId].orEmpty()
            if (local == null) {
                val id = playlistDao.insert(
                    PlaylistEntity(
                        name = p.optString("name", "Playlist"),
                        origin = p.optString("origin", "local"),
                        externalId = p.optString("external_id").takeIf { it.isNotEmpty() && it != "null" },
                        artworkUrl = p.optString("artwork_url").takeIf { it.isNotEmpty() && it != "null" },
                        createdAt = p.optLong("created_at", System.currentTimeMillis()),
                        syncId = syncId,
                        updatedAt = remoteUpdated,
                    )
                )
                playlistDao.replaceEntries(id, keys)
                changes++
            } else if (remoteUpdated > local.updatedAt) {
                playlistDao.updateInfo(local.id, p.optString("name", local.name), p.optString("artwork_url").takeIf { it.isNotEmpty() && it != "null" } ?: local.artworkUrl)
                playlistDao.replaceEntries(local.id, keys)
                playlistDao.touch(local.id, remoteUpdated)
                changes++
            }
        }

        // Match fixes
        for (m in getAll("track_matches", "spotify_key")) {
            val key = m.optString("spotify_key")
            val existing = db.matches().get(key)
            if (existing == null || (m.optBoolean("manual") && !existing.manual)) {
                db.matches().upsert(MatchEntity(key, m.optString("youtube_id"), 1.0, m.optBoolean("manual")))
            }
        }
        changes += pullSubscriptions()
        return changes
    }

    private suspend fun push(): Int {
        val userId = _account.value?.userId ?: return 0
        val playlistDao = db.playlists()

        // Songs
        val done = db.downloads().doneKeysOnce().toSet()
        val tracks = db.tracks().libraryTracks()
        // PostgREST bulk upserts need identical keys per batch, so downloaded rows go separately.
        val (dl, others) = tracks.partition { it.trackKey in done }
        upsert("library_tracks", others.map { it.toJson(userId, false) })
        upsert("library_tracks", dl.map { it.toJson(userId, true) })

        // Playlists: give each a sync id, upload the ones that changed since the cloud copy
        val remote = getAll("playlists", "sync_id").associateBy { it.optString("sync_id") }
        var pushed = 0
        for (p0 in playlistDao.allOnce()) {
            var p = p0
            if (p.syncId == null) {
                val id = UUID.randomUUID().toString()
                playlistDao.setSyncId(p.id, id)
                p = p.copy(syncId = id)
            }
            val syncId = p.syncId!!
            val r = remote[syncId]
            if (r != null && r.optLong("updated_at", 0) >= p.updatedAt) continue
            val updatedAt = maxOf(p.updatedAt, 1L)
            upsert(
                "playlists",
                listOf(
                    JSONObject()
                        .put("user_id", userId).put("sync_id", syncId).put("name", p.name)
                        .put("origin", p.origin).put("external_id", p.externalId ?: JSONObject.NULL)
                        .put("artwork_url", p.artworkUrl ?: JSONObject.NULL)
                        .put("created_at", p.createdAt).put("updated_at", updatedAt).put("deleted", false)
                )
            )
            rest("DELETE", "playlist_items?playlist_sync_id=eq.${enc(syncId)}")
            val entries = playlistDao.entries(p.id)
            upsert("playlist_items", entries.mapIndexed { i, e ->
                JSONObject().put("user_id", userId).put("playlist_sync_id", syncId)
                    .put("position", i).put("track_key", e.trackKey)
            })
            if (p.updatedAt != updatedAt) playlistDao.touch(p.id, updatedAt)
            pushed++
        }

        // Match fixes
        val matches = db.matches().all()
        upsert("track_matches", matches.map {
            JSONObject().put("user_id", userId).put("spotify_key", it.spotifyKey)
                .put("youtube_id", it.youtubeId).put("manual", it.manual)
        })
        pushSubscriptions(userId)
        return pushed
    }

    // ---------------------------------------------------------------- subscriptions

    /** True when the project has no subscriptions table yet (older schema): skip quietly. */
    private var subscriptionsTableMissing = false

    private suspend fun pullSubscriptions(): Int {
        val remote = try {
            getAll("subscriptions", "channel_id")
        } catch (e: CloudHttpException) {
            if (e.code == 404 || e.message?.contains("subscriptions") == true) { subscriptionsTableMissing = true; return 0 }
            throw e
        }
        subscriptionsTableMissing = false
        var changes = 0
        val dao = db.subscriptions()
        for (r in remote) {
            val id = r.optString("channel_id")
            if (id.isEmpty()) continue
            val at = r.optLong("updated_at", 0)
            val local = dao.get(id)
            if (local == null || at > local.subscribedAt) {
                dao.upsert(
                    app.mixtape.data.db.SubscriptionEntity(
                        channelId = id,
                        name = r.optString("name", local?.name ?: ""),
                        avatarUrl = r.optString("avatar_url").takeIf { it.isNotEmpty() && it != "null" } ?: local?.avatarUrl,
                        subscribedAt = at,
                        deleted = r.optBoolean("deleted", false),
                    )
                )
                changes++
            }
        }
        return changes
    }

    private suspend fun pushSubscriptions(userId: String) {
        if (subscriptionsTableMissing) return
        val rows = db.subscriptions().allOnce().map {
            JSONObject().put("user_id", userId).put("channel_id", it.channelId).put("name", it.name)
                .put("avatar_url", it.avatarUrl ?: JSONObject.NULL).put("updated_at", it.subscribedAt)
                .put("deleted", it.deleted)
        }
        upsert("subscriptions", rows)
    }

    // ---------------------------------------------------------------- deletions

    /** Playlist deleted on this phone: mark it deleted in the cloud so other phones remove it too. */
    suspend fun onPlaylistDeleted(syncId: String) {
        val pending = prefs.getStringSet("pending_deletes", emptySet())!!.toMutableSet()
        pending += syncId
        prefs.edit().putStringSet("pending_deletes", pending).apply()
        syncSoon()
    }

    private suspend fun pushPendingDeletes() {
        val userId = _account.value?.userId ?: return
        val pending = prefs.getStringSet("pending_deletes", emptySet())!!.toSet()
        for (syncId in pending) {
            rest(
                "PATCH", "playlists?sync_id=eq.${enc(syncId)}",
                JSONObject().put("deleted", true).put("updated_at", System.currentTimeMillis()).put("user_id", userId),
                prefer = "return=minimal",
            )
            rest("DELETE", "playlist_items?playlist_sync_id=eq.${enc(syncId)}")
        }
        prefs.edit().putStringSet("pending_deletes", emptySet()).apply()
    }

    /** A download was removed on this phone: stop offering it for restore elsewhere. */
    fun onDownloadDeleted(trackKey: String) {
        if (_account.value == null || !isConfigured) return
        scope.launch {
            runCatching {
                rest(
                    "PATCH", "library_tracks?track_key=eq.${enc(trackKey)}",
                    JSONObject().put("downloaded", false).put("downloaded_at", System.currentTimeMillis()),
                    prefer = "return=minimal",
                )
            }
        }
    }

    fun clearRestorable() {
        _restorable.value = emptyList()
    }

    /** Auto-sync: after login, at startup, and a little while after the library changes. */
    @OptIn(FlowPreview::class)
    fun start(changes: kotlinx.coroutines.flow.Flow<Any>) {
        syncSoon()
        scope.launch {
            combine(_account, changes) { a, c -> a to c }
                .drop(1)
                .debounce(15_000)
                .collect { (a, _) -> if (a != null && isConfigured) runCatching { sync() } }
        }
        scope.launch {
            var last: CloudAccount? = _account.value
            _account.collect { a ->
                if (a != null && last == null) runCatching { sync() }
                last = a
            }
        }
    }

    // ---------------------------------------------------------------- mapping

    private fun TrackEntity.toJson(userId: String, downloaded: Boolean) = JSONObject()
        .put("user_id", userId)
        .put("track_key", trackKey)
        .put("source", source)
        .put("source_id", sourceId)
        .put("title", title)
        .put("artist", artist)
        .put("album", album ?: JSONObject.NULL)
        .put("duration_sec", durationSec)
        .put("artwork_url", artworkUrl ?: JSONObject.NULL)
        .put("isrc", isrc ?: JSONObject.NULL)
        .put("liked", liked)
        .put("liked_at", likedAt)
        .apply { if (downloaded) put("downloaded", true).put("downloaded_at", System.currentTimeMillis()) }

    private fun JSONObject.toTrack(): Track? {
        val source = runCatching { SourceType.fromPrefix(optString("source")) }.getOrNull() ?: return null
        val sourceId = optString("source_id").takeIf { it.isNotEmpty() } ?: return null
        fun str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }
        return Track(
            source = source,
            sourceId = sourceId,
            title = optString("title"),
            artist = optString("artist"),
            album = str("album"),
            durationSec = optLong("duration_sec", 0),
            artworkUrl = str("artwork_url"),
            isrc = str("isrc"),
        )
    }
}

private const val OLD_PROJECT_REF = "wytpepmljjzevewxetzf"

class CloudHttpException(val code: Int, message: String) : IOException(message)
