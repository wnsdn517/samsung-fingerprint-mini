package io.github.fpmini

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** Sensor indicator for devices whose fingerprint sensor is not under the display. */
class GlowView(
    context: Context,
    private val kind: SensorKind,
    private val centre: Float?,
    private val length: Float?,
) : View(context) {

    private val painter = GlowPainter(resources.displayMetrics.density)

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val c = centre ?: if (kind == SensorKind.SIDE) h * 0.36f else w / 2f
        painter.draw(canvas, w, h, kind, c, length ?: 0f)
        postInvalidateOnAnimation()
    }
}
