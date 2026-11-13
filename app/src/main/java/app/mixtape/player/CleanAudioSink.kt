package app.mixtape.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What the sound engine last did, shown in the clean-edit editor so problems are visible. */
object CleanDiagnostics {
    @Volatile var lastMediaMs = -1L
    @Volatile var lastSeenAt = 0L
    @Volatile var lastAppliedAt = 0L
    @Volatile var editKey: String? = null
    @Volatile var lastError: String? = null
}

/** How a marked part of a song is cleaned. */
enum class CensorMode(val label: String) {
    BEEP("Bleep"),
    SILENCE("Silence"),

    /** Takes the vocals out and keeps the beat, like the instrumental. */
    INSTRUMENTAL("Instrumental"),

    /** Beat keeps playing with a softer bleep over it, like a radio edit. */
    INSTRUMENTAL_BEEP("Instrumental + bleep"),
}

/**
 * What the audio thread needs to know: the clean edit of the song being decoded now, and of the
 * next one (decoding runs a little ahead, so the next song can start before the UI notices).
 * Updated from the main thread by [CensorEngine].
 */
class CleanEditState {
    @Volatile var current: CensorEdit? = null
    @Volatile var next: CensorEdit? = null
    @Volatile var useNext = false
    @Volatile var offsetMs = 0L

    fun active(): CensorEdit? = if (useNext) next else current
}

/**
 * Sits in front of ExoPlayer's normal audio output and edits the decoded sound inside the marked
 * parts before it's played. Working on the decoded samples (with their exact time in the song)
 * means the edit lands on the word itself, whatever the speaker or Bluetooth delay.
 *
 * "Instrumental" removes what's in the middle of the stereo mix, where vocals almost always sit,
 * and keeps what's panned left/right plus the bass and kick drum. It works well on most studio
 * recordings; on mono recordings only the bass survives.
 */
class CleanAudioSink(sink: AudioSink, private val state: CleanEditState) : ForwardingAudioSink(sink) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 0
    private var offsetUs = 0L
    private var flushedSinceStream = true
    private var lastBuffer: ByteBuffer? = null
    private var lastPts = Long.MIN_VALUE

    // Low-pass filter (keeps bass + kick from the middle of the mix) and bleep oscillator.
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    private var phase = 0.0

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        encoding = inputFormat.pcmEncoding
        channels = inputFormat.channelCount
        rate = inputFormat.sampleRate
        designLowPass(200.0)
        resetFilter()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        if (outputStreamOffsetUs != offsetUs) {
            // A new song started decoding. After a seek the main thread already points at the
            // right song; otherwise (gapless next song) switch to the next song's edit.
            if (!flushedSinceStream) state.useNext = true
            offsetUs = outputStreamOffsetUs
            flushedSinceStream = false
        }
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun flush() {
        flushedSinceStream = true
        state.useNext = false
        pending = null
        lastBuffer = null
        lastPts = Long.MIN_VALUE
        resetFilter()
        super.flush()
    }

    /** What we handed the real output for the current decoder buffer (a writable copy, or the original). */
    private var pending: ByteBuffer? = null
    private val pool = arrayOfNulls<ByteBuffer>(2)
    private var poolIndex = 0

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // The same buffer comes back until the output has room for it; only prepare it once, and
        // keep handing over the same (copied) buffer until it's used up.
        if (buffer !== lastBuffer || presentationTimeUs != lastPts) {
            lastBuffer = buffer
            lastPts = presentationTimeUs
            flushedSinceStream = false
            pending = try {
                prepare(buffer, presentationTimeUs - offsetUs)
            } catch (e: Throwable) {
                CleanDiagnostics.lastError = e.toString()
                null
            }
        }
        val toSink = pending ?: buffer
        val done = super.handleBuffer(toSink, presentationTimeUs, encodedAccessUnitCount)
        if (done && toSink !== buffer) {
            // We played the edited copy; mark the decoder's original as used too.
            buffer.position(buffer.limit())
            pending = null
        }
        return done
    }

    /**
     * The decoder's buffers are read-only, so when this buffer overlaps a marked part we copy it
     * into our own memory, edit the copy and play that. Returns null when nothing needs changing.
     */
    private fun prepare(buffer: ByteBuffer, mediaTimeUs: Long): ByteBuffer? {
        CleanDiagnostics.lastMediaMs = mediaTimeUs / 1000
        CleanDiagnostics.lastSeenAt = System.currentTimeMillis()
        val edit = state.active() ?: return null
        CleanDiagnostics.editKey = edit.trackKey
        if (!overlaps(edit, buffer.remaining(), mediaTimeUs)) return null

        val size = buffer.remaining()
        var work = pool[poolIndex]
        if (work == null || work.capacity() < size) {
            work = ByteBuffer.allocateDirect(maxOf(size, 64 * 1024)).order(ByteOrder.nativeOrder())
            pool[poolIndex] = work
        }
        poolIndex = (poolIndex + 1) % pool.size
        work!!.clear()
        work.put(buffer.duplicate())
        work.flip()
        process(work, mediaTimeUs, edit)
        CleanDiagnostics.lastAppliedAt = System.currentTimeMillis()
        CleanDiagnostics.lastError = null
        return work
    }

    private fun overlaps(edit: CensorEdit, bytes: Int, mediaTimeUs: Long): Boolean {
        if (edit.ranges.isEmpty() || rate <= 0 || channels <= 0) return false
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> { CleanDiagnostics.lastError = "Unsupported audio format ($encoding)"; return false }
        }
        val frames = bytes / (bytesPerSample * channels)
        val startMs = mediaTimeUs / 1000.0 + state.offsetMs
        val endMs = startMs + frames * 1000.0 / rate
        return edit.ranges.any { it.startMs - LEAD_MS - FADE_MS < endMs && it.endMs + TAIL_MS + FADE_MS > startMs }
    }

    private fun process(buffer: ByteBuffer, mediaTimeUs: Long, edit: CensorEdit) {
        if (edit.ranges.isEmpty() || rate <= 0 || channels <= 0) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytesPerSample * channels
        val frames = buffer.remaining() / frameBytes
        if (frames <= 0) return

        val startMs = mediaTimeUs / 1000.0 + state.offsetMs
        val endMs = startMs + frames * 1000.0 / rate
        val hits = edit.ranges.filter { it.startMs - LEAD_MS - FADE_MS < endMs && it.endMs + TAIL_MS + FADE_MS > startMs }
        if (hits.isEmpty()) return

        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val base = buffer.position()
        val msPerFrame = 1000.0 / rate
        val toneStep = 2 * PI * 1000.0 / rate
        val mode = edit.mode

        for (f in 0 until frames) {
            val t = startMs + f * msPerFrame
            var w = 0.0
            for (r in hits) {
                val s = r.startMs - LEAD_MS
                val e = r.endMs + TAIL_MS
                val up = ((t - (s - FADE_MS)) / FADE_MS).coerceIn(0.0, 1.0)
                val down = (((e + FADE_MS) - t) / FADE_MS).coerceIn(0.0, 1.0)
                w = max(w, min(up, down))
            }
            if (w <= 0.0) continue

            val pos = base + f * frameBytes
            val l = read(b, pos, bytesPerSample)
            val r = if (channels > 1) read(b, pos + bytesPerSample, bytesPerSample) else l

            val tone = sin(phase)
            phase += toneStep
            if (phase > 2 * PI) phase -= 2 * PI

            var pl: Double
            var pr: Double
            when (mode) {
                CensorMode.SILENCE -> { pl = 0.0; pr = 0.0 }
                CensorMode.BEEP -> { pl = tone * 0.25; pr = pl }
                CensorMode.INSTRUMENTAL, CensorMode.INSTRUMENTAL_BEEP -> {
                    val mid = (l + r) * 0.5
                    val side = (l - r) * 0.5
                    val low = lowPass(mid)
                    if (channels > 1) {
                        pl = side * SIDE_GAIN + low
                        pr = -side * SIDE_GAIN + low
                    } else {
                        pl = low; pr = low
                    }
                    if (mode == CensorMode.INSTRUMENTAL_BEEP) { pl += tone * 0.12; pr += tone * 0.12 }
                }
            }
            write(b, pos, bytesPerSample, l + (pl - l) * w)
            if (channels > 1) {
                write(b, pos + bytesPerSample, bytesPerSample, r + (pr - r) * w)
                // Any extra channels (surround) go quiet inside the part.
                for (c in 2 until channels) {
                    val p = pos + c * bytesPerSample
                    write(b, p, bytesPerSample, read(b, p, bytesPerSample) * (1 - w))
                }
            }
        }
    }

    private fun read(b: ByteBuffer, pos: Int, size: Int): Double =
        if (size == 2) b.getShort(pos) / 32768.0 else b.getFloat(pos).toDouble()

    private fun write(b: ByteBuffer, pos: Int, size: Int, v: Double) {
        val c = v.coerceIn(-1.0, 1.0)
        if (size == 2) b.putShort(pos, (c * 32767).toInt().toShort()) else b.putFloat(pos, c.toFloat())
    }

    /** Standard second-order low-pass (RBJ cookbook). */
    private fun designLowPass(cutoff: Double) {
        if (rate <= 0) return
        val w0 = 2 * PI * cutoff / rate
        val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
        val cw = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 - cw) / 2 / a0
        b1 = (1 - cw) / a0
        b2 = (1 - cw) / 2 / a0
        a1 = -2 * cw / a0
        a2 = (1 - alpha) / a0
    }

    private fun lowPass(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    private fun resetFilter() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    private companion object {
        const val LEAD_MS = 30.0
        const val TAIL_MS = 30.0
        const val FADE_MS = 15.0
        /** Removing the middle also removes some loudness; bring the beat back up a little. */
        const val SIDE_GAIN = 1.4
    }
}
