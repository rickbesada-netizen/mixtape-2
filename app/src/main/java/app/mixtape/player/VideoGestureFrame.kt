package app.mixtape.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * Wraps the video like YouTube's player: pinch out to zoom the video to fill the screen, pinch in to
 * see the whole picture again, and double-tap the left or right side to skip 10 seconds. It only
 * watches the touches; normal taps still reach the player's own buttons.
 */
@SuppressLint("ViewConstructor")
class VideoGestureFrame(
    context: Context,
    private val onZoom: (fill: Boolean) -> Unit,
    private val onDoubleTapSide: (forward: Boolean) -> Unit,
) : FrameLayout(context) {

    private var scaleTotal = 1f
    /** Set once a second finger lands: the rest of that touch belongs to the pinch, not the buttons. */
    private var pinching = false

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
        if (ev.pointerCount == 1 && !pinching) taps.onTouchEvent(ev)

        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !pinching) {
            pinching = true
            // Tell the player's buttons the touch is gone, so a pinch never presses anything.
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (pinching) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
