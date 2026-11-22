package app.mixtape.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps the video like YouTube's player:
 *  - grab the video and drag it down: it follows your finger and shrinks ([onDrag] / [onDragEnd]);
 *  - pinch out to zoom the video to fill the screen, pinch in to see the whole picture;
 *  - double-tap the left or right side to skip 10 seconds; tap to show / hide the controls.
 */
@SuppressLint("ViewConstructor")
class VideoGestureFrame(
    context: Context,
    private val onZoom: (fill: Boolean) -> Unit,
    private val onDoubleTapSide: (forward: Boolean) -> Unit,
    private val onSingleTap: () -> Unit = {},
    /** How far (in pixels) the finger has dragged the video down so far. */
    private val onDrag: (dyPx: Float) -> Unit = {},
    /** Finger lifted after a drag: total distance and downward speed (pixels / second). */
    private val onDragEnd: (dyPx: Float, velocityY: Float) -> Unit = { _, _ -> },
) : FrameLayout(context) {

    private var scaleTotal = 1f
    /** Set once a second finger lands: the rest of that touch belongs to the pinch. */
    private var pinching = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var dragStartY = 0f
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaleTotal = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleTotal *= detector.scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            when {
                scaleTotal > 1.08f -> onZoom(true)
                scaleTotal < 0.92f -> onZoom(false)
            }
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val x = e.x / width.coerceAtLeast(1)
            when {
                x < 0.4f -> onDoubleTapSide(false)
                x > 0.6f -> onDoubleTapSide(true)
            }
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scale.onTouchEvent(ev)

        // Screen coordinates: the video itself moves and shrinks while being dragged.
        val raw = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!pinching) {
                    pinching = true
                    if (dragging) { dragging = false; onDragEnd(0f, 0f) }
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    taps.onTouchEvent(cancel)
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            MotionEvent.ACTION_MOVE -> if (!pinching) {
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                if (!dragging && dy > slop * 1.5f && dy > abs(dx) * 1.3f) {
                    // It's a drag down, not a tap: stop the tap detector seeing it.
                    dragging = true
                    dragStartY = ev.rawY
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    taps.onTouchEvent(cancel)
                    cancel.recycle()
                }
                if (dragging) onDrag((ev.rawY - dragStartY).coerceAtLeast(0f))
            }
        }
        velocity?.addMovement(raw)
        raw.recycle()

        if (!pinching && !dragging) taps.onTouchEvent(ev)

        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                velocity?.computeCurrentVelocity(1000)
                onDragEnd((ev.rawY - dragStartY).coerceAtLeast(0f), velocity?.yVelocity ?: 0f)
            }
            dragging = false
            pinching = false
            velocity?.recycle()
            velocity = null
            return true
        }
        if (pinching) return true
        super.dispatchTouchEvent(ev)
        // Always keep the touch, so taps and drags are seen even where nothing else wants them.
        return true
    }
}
