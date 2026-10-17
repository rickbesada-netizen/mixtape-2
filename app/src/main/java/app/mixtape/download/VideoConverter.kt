package app.mixtape.download

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Re-encodes a 1440p/4K WebM (VP9 + Opus) into an MP4 with the phone's hardware encoder.
 * HEVC (H.265) is used when the phone can encode it at that size (half the file size of H.264);
 * Media3 falls back to a supported size/codec automatically on weaker phones.
 */
object VideoConverter {

    suspend fun toMp4(
        context: Context,
        input: File,
        output: File,
        height: Int,
        onProgress: suspend (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        output.delete()
        val videoMime = if (canEncode(MimeTypes.VIDEO_H265, height)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

        coroutineScope {
            var transformer: Transformer? = null
            val progressJob = launch {
                val holder = ProgressHolder()
                while (true) {
                    delay(1000)
                    val t = transformer ?: continue
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val t = Transformer.Builder(context)
                        .setVideoMimeType(videoMime)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                if (cont.isActive) {
                                    cont.resumeWithException(IOException("Converting to MP4 failed: ${exportException.message}", exportException))
                                }
                            }
                        })
                        .build()
                    transformer = t
                    cont.invokeOnCancellation {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { t.cancel() } }
                    }
                    t.start(MediaItem.fromUri(Uri.fromFile(input)), output.absolutePath)
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    private fun canEncode(mime: String, height: Int): Boolean = runCatching {
        val width = height * 16 / 9
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(width, height)
        }
    }.getOrDefault(false)
}
