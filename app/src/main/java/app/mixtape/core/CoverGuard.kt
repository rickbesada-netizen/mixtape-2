package app.mixtape.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hides adult images. Every song cover and video thumbnail is checked on the phone (nothing is
 * sent anywhere) by Yahoo's open NSFW model, and ones showing nudity or sexual content are shown
 * heavily blurred instead.
 */
class CoverGuard(private val context: Context, private val enabled: () -> Boolean) : Interceptor {

    private val interpreter: Interpreter? by lazy {
        runCatching {
            val bytes = context.assets.open("cover_guard.tflite").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buf.rewind()
            Interpreter(buf, Interpreter.Options().setNumThreads(2))
        }.getOrNull()
    }

    /** Images already checked: address → adult score (0 to 1). */
    private val scores = LruCache<String, Float>(2000)
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed(chain.request)
        if (!enabled() || result !is SuccessResult) return result
        val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return result
        if (bmp.width < 48 || bmp.height < 48) return result
        val key = chain.request.data.toString()
        val score = scores.get(key) ?: withContext(Dispatchers.Default) { score(bmp) }?.also { scores.put(key, it) } ?: return result
        if (score < THRESHOLD) return result
        val hidden = withContext(Dispatchers.Default) { hide(bmp) }
        return result.copy(drawable = BitmapDrawable(context.resources, hidden))
    }

    /** Adult score for an image: 0 = fine, 1 = explicit. */
    @Synchronized
    fun score(src: Bitmap): Float? {
        val model = interpreter ?: return null
        return runCatching {
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            // Like the model was trained: 256 x 256, then the middle 224 x 224, in BGR minus the average colour.
            val scaled = Bitmap.createScaledBitmap(soft, 256, 256, true)
            scaled.getPixels(pixels, 0, SIZE, 16, 16, SIZE, SIZE)
            input.rewind()
            for (c in pixels) {
                input.putFloat(Color.blue(c) - 103.939f)
                input.putFloat(Color.green(c) - 116.779f)
                input.putFloat(Color.red(c) - 123.68f)
            }
            input.rewind()
            val out = Array(1) { FloatArray(2) }
            model.run(input, out)
            out[0][1]
        }.getOrNull()
    }

    /** Blurred past recognition and darkened. */
    private fun hide(src: Bitmap): Bitmap {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val tiny = Bitmap.createScaledBitmap(soft, 6, (6f * soft.height / soft.width).toInt().coerceAtLeast(4), true)
        val out = Bitmap.createScaledBitmap(tiny, 240, (240f * soft.height / soft.width).toInt().coerceAtLeast(160), true)
        Canvas(out).drawColor(Color.argb(110, 0, 0, 0))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = out.width / 12f
        }
        Canvas(out).drawText("Hidden image", out.width / 2f, out.height / 2f + paint.textSize / 3, paint)
        return out
    }

    companion object {
        private const val SIZE = 224
        /** Yahoo suggests above 0.8 is explicit; a little lower catches suggestive covers too. */
        private const val THRESHOLD = 0.6f
    }
}
