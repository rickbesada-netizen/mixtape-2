package app.mixtape.source

import app.mixtape.core.model.CollectionDetail
import app.mixtape.core.model.RemoteCollection
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Deezer's public catalogue (api.deezer.com): no account or key needed. It has thousands of
 * editorial and user playlists, charts per genre and full track lists. Songs play through their
 * YouTube match, the same way Spotify songs do.
 */
class DeezerApi(private val http: OkHttpClient) {

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val b = "https://api.deezer.com/$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val req = Request.Builder().url(b.build()).header("User-Agent", "Mixtape").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Deezer error ${r.code}")
            val o = JSONObject(r.body?.string().orEmpty())
            o.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message", "error")}") }
            o
        }
    }

    /** The 100 most popular playlists on Deezer right now (all genres). */
    suspend fun topPlaylists(limit: Int = 100): List<RemoteCollection> =
        playlistsFrom(get("chart/0/playlists", mapOf("limit" to limit.toString())))

    /** Deezer's genres (Pop, Rap/Hip Hop, Dance...), as (id, name). */
    suspend fun genres(): List<Pair<Long, String>> {
        val arr = get("genre").optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val g = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = g.optLong("id")
            if (id == 0L) null else id to g.optString("name")
        }
    }

    /** Top playlists for a genre. */
    suspend fun genrePlaylists(genreId: Long, limit: Int = 25): List<RemoteCollection> =
        playlistsFrom(get("chart/$genreId/playlists", mapOf("limit" to limit.toString())))

    suspend fun searchPlaylists(query: String, limit: Int = 25): List<RemoteCollection> =
        playlistsFrom(get("search/playlist", mapOf("q" to query, "limit" to limit.toString())))

    private fun playlistsFrom(o: JSONObject): List<RemoteCollection> {
        val arr = o.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = p.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val count = p.optLong("nb_tracks", -1)
            val owner = p.optJSONObject("user")?.optString("name").orEmpty()
                .ifBlank { p.optJSONObject("creator")?.optString("name").orEmpty() }
            RemoteCollection(
                url = "https://www.deezer.com/playlist/$id",
                title = p.optString("title", "Playlist"),
                subtitle = listOf(if (owner.isBlank() || owner == "Deezer") "Deezer" else "$owner · Deezer", if (count > 0) "$count songs" else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                artworkUrl = p.optString("picture_xl").ifBlank { p.optString("picture_big") }.ifBlank { null },
                trackCount = count,
            )
        }
    }

    suspend fun playlist(id: String): CollectionDetail {
        val p = get("playlist/$id")
        val tracks = mutableListOf<Track>()
        var index = 0
        while (tracks.size < MAX_TRACKS) {
            val page = get("playlist/$id/tracks", mapOf("index" to index.toString(), "limit" to "100"))
            val arr = page.optJSONArray("data") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { trackFrom(it, null) }?.let { tracks += it }
            index += arr.length()
            if (page.optString("next").isBlank()) break
        }
        if (tracks.isEmpty()) throw IOException("That Deezer playlist looks empty")
        val owner = p.optJSONObject("creator")?.optString("name").orEmpty()
        return CollectionDetail(
            RemoteCollection(
                url = "https://www.deezer.com/playlist/$id",
                title = p.optString("title", "Deezer playlist"),
                subtitle = listOf(owner, "Deezer playlist").filter { it.isNotBlank() }.joinToString(" · "),
                artworkUrl = p.optString("picture_xl").ifBlank { null },
                trackCount = tracks.size.toLong(),
            ),
            tracks.distinctBy { it.key },
        )
    }

    suspend fun album(id: String): CollectionDetail {
        val a = get("album/$id")
        val cover = a.optString("cover_xl").ifBlank { null }
        val arr = a.optJSONObject("tracks")?.optJSONArray("data")
        val tracks = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { trackFrom(it, a.optString("title")) }?.let { t -> if (t.artworkUrl == null) t.copy(artworkUrl = cover) else t }
        }
        if (tracks.isEmpty()) throw IOException("That Deezer album looks empty")
        return CollectionDetail(
            RemoteCollection(
                url = "https://www.deezer.com/album/$id",
                title = a.optString("title", "Deezer album"),
                subtitle = listOf(a.optJSONObject("artist")?.optString("name").orEmpty(), "Deezer album").filter { it.isNotBlank() }.joinToString(" · "),
                artworkUrl = cover,
                trackCount = tracks.size.toLong(),
            ),
            tracks,
        )
    }

    suspend fun track(id: String): Track =
        trackFrom(get("track/$id"), null) ?: throw IOException("Couldn't read that Deezer song")

    private fun trackFrom(t: JSONObject, albumTitle: String?): Track? {
        val id = t.optLong("id").takeIf { it > 0 } ?: return null
        if (t.has("readable") && !t.optBoolean("readable", true) && t.optString("title").isBlank()) return null
        val album = t.optJSONObject("album")
        return Track(
            source = SourceType.DEEZER,
            sourceId = id.toString(),
            title = t.optString("title_short").ifBlank { t.optString("title") }.ifBlank { return null },
            artist = t.optJSONObject("artist")?.optString("name").orEmpty(),
            album = album?.optString("title")?.ifBlank { null } ?: albumTitle,
            durationSec = t.optLong("duration", 0),
            artworkUrl = album?.optString("cover_xl")?.ifBlank { null } ?: album?.optString("cover_big")?.ifBlank { null },
            isrc = t.optString("isrc").ifBlank { null },
        )
    }

    private companion object {
        const val MAX_TRACKS = 1000
    }
}
