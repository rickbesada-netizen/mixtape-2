package app.mixtape.data

import android.content.Context
import android.net.Uri
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.data.db.AppDatabase
import app.mixtape.data.db.DownloadEntity
import app.mixtape.data.db.DownloadState
import app.mixtape.data.db.MatchEntity
import app.mixtape.data.db.PlaylistEntity
import app.mixtape.data.db.TrackEntity
import app.mixtape.download.AesCtr
import app.mixtape.download.CryptoManager
import app.mixtape.download.DownloadController
import app.mixtape.download.EncryptedFileReader
import app.mixtape.download.EncryptedFileWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec

data class BackupState(
    val running: Boolean = false,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
    val isError: Boolean = false,
    /** Songs listed in an imported backup whose audio wasn't in it, ready to download again. */
    val missing: List<Track> = emptyList(),
)

/**
 * Backups that survive uninstalling the app or moving phones.
 *
 *  - Library only (.json, tiny): liked songs, every playlist in order, Spotify match fixes and
 *    the list of what was downloaded. Importing offers to download the songs again.
 *  - Everything (.mixtape): the same plus the downloaded audio and video files themselves, so
 *    nothing needs downloading again. The files stay encrypted inside the backup, so it still
 *    only plays in Mixtape.
 *
 * Backup layout (.mixtape is a zip): manifest.json, key.bin, then media/<sha1>.mxt, each media
 * file being [16-byte IV][AES-CTR ciphertext] under the backup's own key.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val library: LibraryRepository,
    private val crypto: CryptoManager,
    private val downloads: DownloadController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, missing = emptyList())
    }

    /** Size of everything that a full backup would include. */
    suspend fun downloadedBytes(): Long = db.downloads().doneOnce().sumOf { it.bytesTotal }

    fun export(uri: Uri, includeMedia: Boolean) {
        if (_state.value.running) return
        scope.launch {
            _state.value = BackupState(running = true, label = "Preparing backup...")
            runCatching { doExport(uri, includeMedia) }
                .onSuccess { msg -> _state.value = BackupState(message = msg) }
                .onFailure { e -> _state.value = BackupState(message = "Backup failed: ${e.message}", isError = true) }
        }
    }

    fun importBackup(uri: Uri) {
        if (_state.value.running) return
        scope.launch {
            _state.value = BackupState(running = true, label = "Reading backup...")
            runCatching { doImport(uri) }
                .onSuccess { (msg, missing) -> _state.value = BackupState(message = msg, missing = missing) }
                .onFailure { e -> _state.value = BackupState(message = "Import failed: ${e.message}", isError = true) }
        }
    }

    // ------------------------------------------------------------------------------- export

    private suspend fun buildManifest(includeMedia: Boolean): Pair<JSONObject, List<DownloadEntity>> {
        val tracks = db.tracks().libraryTracks()
        val done = db.downloads().doneOnce().filter { d -> d.filePath?.let { File(it).exists() } == true }
        val knownKeys = tracks.map { it.trackKey }.toSet()
        // Downloaded videos/songs whose track row isn't in the library query still get exported.
        val extraTracks = db.tracks().getMany(done.map { it.trackKey }.filter { it !in knownKeys })

        val tracksJson = JSONArray()
        (tracks + extraTracks).distinctBy { it.trackKey }.forEach { t ->
            tracksJson.put(
                JSONObject()
                    .put("key", t.trackKey).put("source", t.source).put("sourceId", t.sourceId)
                    .put("title", t.title).put("artist", t.artist).put("album", t.album ?: JSONObject.NULL)
                    .put("durationSec", t.durationSec).put("artworkUrl", t.artworkUrl ?: JSONObject.NULL)
                    .put("isrc", t.isrc ?: JSONObject.NULL).put("liked", t.liked).put("likedAt", t.likedAt)
            )
        }

        val playlistsJson = JSONArray()
        for (p in db.playlists().allOnce()) {
            val keys = JSONArray()
            db.playlists().entries(p.id).forEach { keys.put(it.trackKey) }
            playlistsJson.put(
                JSONObject()
                    .put("syncId", p.syncId ?: UUID.randomUUID().toString())
                    .put("name", p.name).put("origin", p.origin)
                    .put("externalId", p.externalId ?: JSONObject.NULL)
                    .put("artworkUrl", p.artworkUrl ?: JSONObject.NULL)
                    .put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)
                    .put("tracks", keys)
            )
        }

        val matchesJson = JSONArray()
        db.matches().all().forEach {
            matchesJson.put(JSONObject().put("spotifyKey", it.spotifyKey).put("youtubeId", it.youtubeId).put("manual", it.manual))
        }

        val downloadsJson = JSONArray()
        done.forEach { d ->
            downloadsJson.put(
                JSONObject().put("key", d.trackKey).put("bytes", d.bytesTotal)
                    .put("entry", if (includeMedia) mediaEntry(d.trackKey) else JSONObject.NULL)
            )
        }

        val subsJson = JSONArray()
        db.subscriptions().activeOnce().forEach {
            subsJson.put(JSONObject().put("channelId", it.channelId).put("name", it.name).put("avatarUrl", it.avatarUrl ?: JSONObject.NULL))
        }

        val censorJson = JSONArray()
        db.censor().allOnce().filter { !it.deleted }.forEach {
            censorJson.put(JSONObject().put("key", it.trackKey).put("ranges", it.ranges).put("beep", it.beep).put("updatedAt", it.updatedAt))
        }

        val manifest = JSONObject()
            .put("app", "Mixtape").put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("includesMedia", includeMedia)
            .put("tracks", tracksJson).put("playlists", playlistsJson)
            .put("matches", matchesJson).put("downloads", downloadsJson)
            .put("subscriptions", subsJson)
            .put("cleanEdits", censorJson)
        return manifest to done
    }

    private fun mediaEntry(key: String) = "media/" + DownloadController.sha1(key) + ".mxt"

    private suspend fun doExport(uri: Uri, includeMedia: Boolean): String {
        val (manifest, done) = buildManifest(includeMedia)
        val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't create the backup file")
        val playlistCount = manifest.getJSONArray("playlists").length()
        val trackCount = manifest.getJSONArray("tracks").length()

        if (!includeMedia) {
            out.use { it.write(manifest.toString(1).toByteArray()) }
            return "Saved your library: $playlistCount playlists, $trackCount songs (${done.size} marked as downloaded)."
        }

        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val backupKey = SecretKeySpec(rawKey, "AES")
        ZipOutputStream(BufferedOutputStream(out, 1 shl 16)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("key.bin"))
            zip.write(rawKey)
            zip.closeEntry()

            val buffer = ByteArray(256 * 1024)
            done.forEachIndexed { i, d ->
                val title = db.tracks().get(d.trackKey)?.title ?: d.trackKey
                _state.value = BackupState(running = true, label = "Saving $title", done = i, total = done.size)
                val file = File(d.filePath!!)
                val iv = ByteArray(CryptoManager.HEADER_SIZE).also { SecureRandom().nextBytes(it) }
                val ctr = AesCtr(backupKey, iv, 0)
                zip.putNextEntry(ZipEntry(mediaEntry(d.trackKey)))
                zip.write(iv)
                EncryptedFileReader(file, crypto.dataKey).use { reader ->
                    reader.seek(0)
                    while (true) {
                        val n = reader.read(buffer, 0, buffer.size)
                        if (n <= 0) break
                        ctr.process(buffer, 0, n)
                        zip.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
        val mb = done.sumOf { it.bytesTotal } / 1_048_576.0
        return "Backup saved: $playlistCount playlists, $trackCount songs and ${done.size} downloads (" +
            "%.0f MB).".format(mb)
    }

    // ------------------------------------------------------------------------------- import

    private suspend fun doImport(uri: Uri): Pair<String, List<Track>> {
        val raw = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
        BufferedInputStream(raw, 1 shl 16).use { input ->
            input.mark(4)
            val head = ByteArray(2)
            val n = input.read(head)
            input.reset()
            return if (n == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                importZip(input)
            } else {
                val manifest = JSONObject(input.readBytes().toString(Charsets.UTF_8))
                applyManifest(manifest)
                finishImport(manifest, restored = emptySet())
            }
        }
    }

    private suspend fun importZip(input: InputStream): Pair<String, List<Track>> {
        val zip = ZipInputStream(input)
        var manifest: JSONObject? = null
        var backupKey: SecretKeySpec? = null
        val entryToKey = HashMap<String, String>()
        val restored = HashSet<String>()
        var total = 0
        val buffer = ByteArray(256 * 1024)

        while (true) {
            val entry = zip.nextEntry ?: break
            when {
                entry.name == "manifest.json" -> {
                    val m = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    if (m.optString("app") != "Mixtape") throw IOException("This isn't a Mixtape backup")
                    applyManifest(m)
                    val dls = m.optJSONArray("downloads") ?: JSONArray()
                    for (i in 0 until dls.length()) {
                        val d = dls.getJSONObject(i)
                        d.optString("entry").takeIf { it.isNotEmpty() && it != "null" }?.let { entryToKey[it] = d.getString("key") }
                    }
                    total = entryToKey.size
                    manifest = m
                }
                entry.name == "key.bin" -> backupKey = SecretKeySpec(zip.readBytes(), "AES")
                entry.name.startsWith("media/") -> {
                    val key = entryToKey[entry.name]
                    val bk = backupKey
                    if (key == null || bk == null) {
                        zip.closeEntry(); continue
                    }
                    val title = db.tracks().get(key)?.title ?: key
                    _state.value = BackupState(running = true, label = "Restoring $title", done = restored.size, total = total)
                    val iv = ByteArray(CryptoManager.HEADER_SIZE)
                    readFully(zip, iv)
                    val ctr = AesCtr(bk, iv, 0)
                    val dest = downloads.fileFor(key)
                    dest.delete()
                    var size = 0L
                    EncryptedFileWriter(dest, crypto.dataKey, 0).use { writer ->
                        while (true) {
                            val r = zip.read(buffer)
                            if (r < 0) break
                            ctr.process(buffer, 0, r)
                            writer.write(buffer, 0, r)
                            size += r
                        }
                    }
                    db.downloads().upsert(
                        DownloadEntity(
                            trackKey = key, state = DownloadState.DONE, progress = 1f,
                            bytesDone = size, bytesTotal = size, filePath = dest.absolutePath,
                        )
                    )
                    restored += key
                }
            }
            zip.closeEntry()
        }
        val m = manifest ?: throw IOException("The backup is missing its manifest")
        return finishImport(m, restored)
    }

    private suspend fun finishImport(manifest: JSONObject, restored: Set<String>): Pair<String, List<Track>> {
        val doneNow = db.downloads().doneOnce().map { it.trackKey }.toSet()
        val dls = manifest.optJSONArray("downloads") ?: JSONArray()
        val missingKeys = (0 until dls.length()).map { dls.getJSONObject(it).getString("key") }.filter { it !in doneNow }
        val missing = db.tracks().getMany(missingKeys).map { it.toTrack() }
        val playlists = manifest.optJSONArray("playlists")?.length() ?: 0
        val tracks = manifest.optJSONArray("tracks")?.length() ?: 0
        val msg = buildString {
            append("Imported $playlists playlists and $tracks songs")
            if (restored.isNotEmpty()) append(", restored ${restored.size} downloads")
            append(".")
            if (missing.isNotEmpty()) append(" ${missing.size} downloaded songs weren't in this backup.")
        }
        return msg to missing
    }

    /** Merges a backup into the library: adds songs and playlists, keeps what's already here. */
    private suspend fun applyManifest(m: JSONObject) {
        val tracksJson = m.optJSONArray("tracks") ?: JSONArray()
        val tracks = mutableListOf<Track>()
        val likes = mutableListOf<Triple<String, Boolean, Long>>()
        for (i in 0 until tracksJson.length()) {
            val o = tracksJson.getJSONObject(i)
            val source = runCatching { SourceType.fromPrefix(o.getString("source")) }.getOrNull() ?: continue
            fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            tracks += Track(
                source = source, sourceId = o.getString("sourceId"), title = o.optString("title"),
                artist = o.optString("artist"), album = str("album"), durationSec = o.optLong("durationSec"),
                artworkUrl = str("artworkUrl"), isrc = str("isrc"),
            )
            if (o.optBoolean("liked")) likes += Triple(o.getString("key"), true, o.optLong("likedAt"))
        }
        library.saveTracks(tracks)
        for ((key, liked, at) in likes) {
            val existing: TrackEntity? = db.tracks().get(key)
            if (existing != null && !existing.liked) db.tracks().setLiked(key, liked, at)
        }

        val pl = m.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until pl.length()) {
            val o = pl.getJSONObject(i)
            val keysJson = o.optJSONArray("tracks") ?: JSONArray()
            val keys = (0 until keysJson.length()).map { keysJson.getString(it) }
            val syncId = o.optString("syncId").takeIf { it.isNotEmpty() && it != "null" }
            val existing = syncId?.let { db.playlists().bySyncId(it) }
            if (existing != null) {
                if (db.playlists().entries(existing.id).isEmpty()) db.playlists().replaceEntries(existing.id, keys)
                continue
            }
            val id = db.playlists().insert(
                PlaylistEntity(
                    name = o.optString("name", "Playlist"),
                    origin = o.optString("origin", "local"),
                    externalId = o.optString("externalId").takeIf { it.isNotEmpty() && it != "null" },
                    artworkUrl = o.optString("artworkUrl").takeIf { it.isNotEmpty() && it != "null" },
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    syncId = syncId,
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                )
            )
            db.playlists().replaceEntries(id, keys)
        }

        val sj = m.optJSONArray("subscriptions") ?: JSONArray()
        for (i in 0 until sj.length()) {
            val o = sj.getJSONObject(i)
            val id = o.optString("channelId")
            if (id.isEmpty() || db.subscriptions().get(id)?.deleted == false) continue
            db.subscriptions().upsert(
                app.mixtape.data.db.SubscriptionEntity(
                    id, o.optString("name"), o.optString("avatarUrl").takeIf { it.isNotEmpty() && it != "null" },
                    System.currentTimeMillis(),
                )
            )
        }

        val cj = m.optJSONArray("cleanEdits") ?: JSONArray()
        for (i in 0 until cj.length()) {
            val o = cj.getJSONObject(i)
            val key = o.optString("key")
            if (key.isEmpty()) continue
            val at = o.optLong("updatedAt", System.currentTimeMillis())
            val local = db.censor().get(key)
            if (local == null || at > local.updatedAt) {
                db.censor().upsert(app.mixtape.data.db.CensorEntity(key, o.optString("ranges"), o.optBoolean("beep", true), at))
            }
        }

        val mj = m.optJSONArray("matches") ?: JSONArray()
        for (i in 0 until mj.length()) {
            val o = mj.getJSONObject(i)
            val key = o.getString("spotifyKey")
            val current = db.matches().get(key)
            if (current == null || (o.optBoolean("manual") && !current.manual)) {
                db.matches().upsert(MatchEntity(key, o.getString("youtubeId"), 1.0, o.optBoolean("manual")))
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw IOException("Backup file is damaged")
            off += r
        }
    }
}
