package app.mixtape.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mixtape.MixtapeApp
import app.mixtape.core.model.ResolvedStream
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.core.net.HttpStatusException
import app.mixtape.core.net.YoutubeStreamHttp
import app.mixtape.core.net.executeChecked
import app.mixtape.core.net.rangedGet
import app.mixtape.data.LibraryRepository
import app.mixtape.source.YouTubeVideo
import app.mixtape.data.Settings
import app.mixtape.data.db.DownloadDao
import app.mixtape.data.db.DownloadEntity
import app.mixtape.data.db.DownloadState
import app.mixtape.data.db.DownloadWithTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class DownloadController(
    private val context: Context,
    private val dao: DownloadDao,
    private val library: LibraryRepository,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dir: File = File(context.filesDir, "offline").apply { mkdirs() }

    fun all(): Flow<List<DownloadWithTrack>> = dao.allWithTracks()
    fun doneKeys(): Flow<Set<String>> = dao.doneKeys().map { it.toSet() }

    fun fileFor(key: String): File = File(dir, sha1(key) + ".mxt")

    fun enqueue(tracks: List<Track>) {
        scope.launch {
            library.saveTracks(tracks)
            tracks.forEach { t ->
                val existing = dao.get(t.key)
                if (existing?.state == DownloadState.DONE) return@forEach
                dao.upsert(DownloadEntity(trackKey = t.key, state = DownloadState.QUEUED))
                schedule(t.key)
            }
        }
    }

    fun retry(key: String) {
        scope.launch {
            dao.setState(key, DownloadState.QUEUED, null)
            schedule(key, ExistingWorkPolicy.REPLACE)
        }
    }

    fun cancel(key: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(key))
        scope.launch {
            val d = dao.get(key)
            if (d?.state != DownloadState.DONE) {
                fileFor(key).delete()
                dao.delete(key)
            }
        }
    }

    fun delete(key: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(key))
        scope.launch {
            fileFor(key).delete()
            dao.delete(key)
        }
    }

    /** The decrypted-on-the-fly offline file for a key, if fully downloaded. */
    suspend fun completedFile(key: String): File? {
        val d = dao.get(key) ?: return null
        if (d.state != DownloadState.DONE) return null
        val f = d.filePath?.let(::File) ?: return null
        return f.takeIf { it.exists() }
    }

    private fun schedule(key: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.wifiOnlyDownloads.value) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY to key))
            .setConstraints(constraints)
            .addTag(DownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(key), policy, request)
    }

    private fun workName(key: String) = "download-" + sha1(key)

    companion object {
        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        const val CHANNEL_ID = "downloads"
    }
}

class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val container = (appContext as MixtapeApp).container
    private val dao = container.db.downloads()
    private val http: OkHttpClient = container.http

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY) ?: return Result.failure()
        val track = container.library.getTrack(key)
        runCatching { setForeground(foregroundInfo(track?.title ?: "Downloading", 0)) }

        return slots.withPermit {
            dao.setState(key, DownloadState.RUNNING, null)
            try {
                val file = container.downloads.fileFor(key)
                if (SourceType.fromKey(key) == SourceType.YOUTUBE_VIDEO) {
                    val size = downloadVideo(key, file, track?.title)
                    dao.markDone(key, file.absolutePath, size)
                    return@withPermit Result.success()
                }
                val playableKey = if (SourceType.fromKey(key) == SourceType.SPOTIFY) {
                    Track.keyOf(SourceType.YOUTUBE, container.matcher.resolveYoutubeId(key))
                } else key
                val source = container.sources.forType(SourceType.fromKey(playableKey))
                val sourceId = Track.sourceIdOf(playableKey)

                var stream = source.resolveStream(sourceId, forDownload = true)
                val size = try {
                    download(key, stream, file, track?.title)
                } catch (e: HttpStatusException) {
                    if (e.code != 403 && e.code != 410) throw e
                    // Expired or rejected URL: resolve a fresh one and resume.
                    stream = source.resolveStream(sourceId, forDownload = true)
                    download(key, stream, file, track?.title)
                }
                dao.markDone(key, file.absolutePath, size)
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (runAttemptCount < 2) {
                    dao.setState(key, DownloadState.QUEUED, e.message)
                    Result.retry()
                } else {
                    dao.setState(key, DownloadState.FAILED, e.message ?: e.javaClass.simpleName)
                    Result.failure()
                }
            }
        }
    }

    private suspend fun download(key: String, stream: ResolvedStream, file: File, title: String?): Long =
        withContext(Dispatchers.IO) {
            if (stream.isHls) downloadHls(key, stream, file, title) else downloadProgressive(key, stream, file, title)
        }

    private suspend fun downloadProgressive(key: String, stream: ResolvedStream, file: File, title: String?): Long {
        val crypto = container.crypto
        var done = EncryptedFileWriter.existingPlainLength(file)
        var total = -1L
        var lastReported = -1
        val buffer = ByteArray(64 * 1024)

        EncryptedFileWriter(file, crypto.dataKey, done).use { writer ->
            done = writer.plainBytesWritten
            while (total < 0 || done < total) {
                currentCoroutineContext().ensureActive()
                val end = done + CHUNK - 1
                val request = if (stream.isYoutube) YoutubeStreamHttp.build(stream.url, done, end)
                else rangedGet(stream.url, done, end)

                val response = try {
                    http.executeChecked(request)
                } catch (e: HttpStatusException) {
                    // 416 = we already have every byte
                    if (e.code == 416 && total > 0) break else throw e
                }
                response.use { r ->
                    val body = r.body ?: throw IOException("Empty response")
                    var skip = 0L
                    if (r.code == 206) {
                        total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: total
                    } else {
                        // Server ignored the range and sent the whole file.
                        total = body.contentLength().takeIf { it > 0 } ?: total
                        skip = done
                    }
                    val input = body.byteStream()
                    while (skip > 0) {
                        val s = input.skip(skip)
                        if (s <= 0) break
                        skip -= s
                    }
                    var gotBytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        writer.write(buffer, 0, n)
                        done += n
                        gotBytes += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                dao.setProgress(key, done.toFloat() / total, done, total)
                                runCatching { setForeground(foregroundInfo(title ?: "Downloading", pct)) }
                            }
                        }
                    }
                    if (gotBytes == 0L) {
                        if (total < 0) total = done else throw IOException("Download stalled")
                    }
                    if (r.code != 206 && total < 0) total = done
                }
            }
        }
        if (total > 0 && done < total) throw IOException("Incomplete download")
        return done
    }

    /** SoundCloud HLS: fetch the media playlist and concatenate its MP3 segments. */
    private suspend fun downloadHls(key: String, stream: ResolvedStream, file: File, title: String?): Long {
        var playlistUrl = stream.url
        var text = fetchText(playlistUrl)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw IOException("Empty HLS master playlist")
            playlistUrl = playlistUrl.toHttpUrl().resolve(variant.trim())!!.toString()
            text = fetchText(playlistUrl)
        }
        val keyLine = text.lines().firstOrNull { it.startsWith("#EXT-X-KEY") }
        if (keyLine != null && !keyLine.contains("METHOD=NONE")) {
            throw IOException("This SoundCloud track is protected and can only be streamed")
        }
        val base = playlistUrl.toHttpUrl()
        val segments = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { base.resolve(it)!!.toString() }
        if (segments.isEmpty()) throw IOException("HLS playlist has no segments")

        file.delete()
        var done = 0L
        val buffer = ByteArray(64 * 1024)
        EncryptedFileWriter(file, container.crypto.dataKey, 0).use { writer ->
            segments.forEachIndexed { i, seg ->
                currentCoroutineContext().ensureActive()
                http.executeChecked(rangedGet(seg, 0, null)).use { r ->
                    val input = r.body?.byteStream() ?: throw IOException("Empty segment")
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        writer.write(buffer, 0, n)
                        done += n
                    }
                }
                val progress = (i + 1).toFloat() / segments.size
                dao.setProgress(key, progress, done, 0)
                runCatching { setForeground(foregroundInfo(title ?: "Downloading", (progress * 100).toInt())) }
            }
        }
        return done
    }

    // ------------------------------------------------------------------ MP4 video

    private var lastPct = -1

    /** Downloads video + audio, merges them into one MP4, then stores it encrypted like songs. */
    private suspend fun downloadVideo(key: String, file: File, title: String?): Long = withContext(Dispatchers.IO) {
        val options = YouTubeVideo.options(Track.sourceIdOf(key))
        val choice = options.pick(container.settings.videoQuality.value)
        val label = title ?: options.title
        val tmpDir = File(applicationContext.cacheDir, "video-dl").apply { mkdirs() }
        val base = DownloadController.sha1(key) + "_" + choice.height
        val videoFile = File(tmpDir, "$base.v.mp4")
        val audioFile = File(tmpDir, "$base.a.m4a")
        val outFile = File(tmpDir, "$base.out.mp4")

        val videoShare = if (choice.audioUrl == null || choice.muxed) 0.95f else 0.8f
        fetchPlain(choice.videoUrl, videoFile) { done, total -> report(key, label, 0f, videoShare, done, total) }

        val audioUrl = choice.audioUrl
        val merged = if (choice.muxed || audioUrl == null) videoFile else {
            fetchPlain(audioUrl, audioFile) { done, total -> report(key, label, 0.8f, 0.15f, done, total) }
            outFile.delete()
            VideoMuxer.mux(videoFile, audioFile, outFile)
            outFile
        }

        file.delete()
        var size = 0L
        val buffer = ByteArray(256 * 1024)
        EncryptedFileWriter(file, container.crypto.dataKey, 0).use { writer ->
            merged.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    writer.write(buffer, 0, n)
                    size += n
                }
            }
        }
        videoFile.delete()
        audioFile.delete()
        outFile.delete()
        size
    }

    private suspend fun report(key: String, title: String, base: Float, span: Float, done: Long, total: Long) {
        if (total <= 0) return
        val progress = base + span * (done.toFloat() / total)
        val pct = (progress * 100).toInt()
        if (pct == lastPct) return
        lastPct = pct
        dao.setProgress(key, progress, done, total)
        runCatching { setForeground(foregroundInfo(title, pct)) }
    }

    /** Ranged download into a plain temp file, resuming from whatever is already there. */
    private suspend fun fetchPlain(url: String, file: File, onProgress: suspend (Long, Long) -> Unit) {
        var done = if (file.exists()) file.length() else 0L
        var total = -1L
        val buffer = ByteArray(64 * 1024)
        while (total < 0 || done < total) {
            currentCoroutineContext().ensureActive()
            val request = YoutubeStreamHttp.build(url, done, done + CHUNK - 1)
            val response = try {
                http.executeChecked(request)
            } catch (e: HttpStatusException) {
                if (e.code == 416 && done > 0) break else throw e
            }
            response.use { r ->
                val body = r.body ?: throw IOException("Empty response")
                val append = r.code == 206
                if (append) {
                    total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: total
                } else {
                    total = body.contentLength().takeIf { it > 0 } ?: -1L
                    done = 0
                }
                var got = 0L
                java.io.FileOutputStream(file, append).use { out ->
                    val input = body.byteStream()
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        got += n
                    }
                }
                onProgress(done, total)
                if (got == 0L) {
                    if (total < 0) total = done else throw IOException("Download stalled")
                }
                if (!append && total < 0) total = done
            }
        }
    }

    private fun fetchText(url: String): String =
        http.executeChecked(rangedGet(url, 0, null)).use { it.body?.string().orEmpty() }

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, DownloadController.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Downloading for offline")
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notificationId = NOTIFICATION_BASE + (this.id.hashCode() and 0xFFFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY = "track_key"
        const val TAG = "mixtape-download"
        private const val CHUNK = 1L * 1024 * 1024
        private const val NOTIFICATION_BASE = 2000

        /** Max parallel downloads across the process. */
        private val slots = Semaphore(3)
    }
}
