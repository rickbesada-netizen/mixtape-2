package app.mixtape.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Merges separate video and audio files into one MP4 (H.264+AAC) or WebM (VP9+Opus) without re-encoding. */
object VideoMuxer {
    fun mux(video: File, audio: File, out: File, webm: Boolean = false) {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoEx.setDataSource(video.absolutePath)
            audioEx.setDataSource(audio.absolutePath)
            val vTrack = findTrack(videoEx, "video/") ?: throw IOException("No video track found")
            val aTrack = findTrack(audioEx, "audio/") ?: throw IOException("No audio track found")
            videoEx.selectTrack(vTrack)
            audioEx.selectTrack(aTrack)

            val m = MediaMuxer(
                out.absolutePath,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = m
            val vOut = m.addTrack(videoEx.getTrackFormat(vTrack))
            val aOut = m.addTrack(audioEx.getTrackFormat(aTrack))
            m.start()

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            // Interleave by timestamp so players can stream the result smoothly.
            var vDone = false
            var aDone = false
            while (!vDone || !aDone) {
                val takeVideo = when {
                    vDone -> false
                    aDone -> true
                    else -> videoEx.sampleTime <= audioEx.sampleTime
                }
                val ex = if (takeVideo) videoEx else audioEx
                val track = if (takeVideo) vOut else aOut
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) {
                    if (takeVideo) vDone = true else aDone = true
                    continue
                }
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, ex.sampleTime, flags)
                m.writeSampleData(track, buffer, info)
                ex.advance()
            }
            m.stop()
        } finally {
            runCatching { muxer?.release() }
            videoEx.release()
            audioEx.release()
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }
}
