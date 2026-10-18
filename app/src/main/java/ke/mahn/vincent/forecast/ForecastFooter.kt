package ke.mahn.vincent.forecast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which moment each frame of a forecast video shows; times are interpolated linearly between first and last frame. */
data class FrameTimeline(val startMs: Long, val endMs: Long, val frameCount: Int) {
    fun progressAt(index: Int): Float =
        if (frameCount <= 1) 1f else (index.toFloat() / (frameCount - 1)).coerceIn(0f, 1f)

    // Integer maths with rounding: a float fraction can land just below a minute boundary and repeat/skip minutes.
    fun timeAt(index: Int): Long {
        if (frameCount <= 1) return endMs
        val steps = (frameCount - 1).toLong()
        val clamped = index.coerceIn(0, frameCount - 1).toLong()
        return startMs + Math.floorDiv((endMs - startMs) * clamped * 2 + steps, steps * 2)
    }
}

/**
 * Draws the time box the server omits with `noFooter=1`: a translucent bar along the bottom with the
 * frame's clock time and a progress fill. Geometry matches the server's 512 px layout and scales with the frame.
 */
class ForecastFooter(zone: ZoneId = ZoneId.systemDefault()) {
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    // Lazy so that time formatting works without the Android graphics stack (unit tests).
    private val background by lazy { Paint().apply { color = 0xB3FFFFFF.toInt() } }
    private val progress by lazy { Paint().apply { color = 0x4D172A35 } }
    private val text by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF172A35.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
        }
    }

    fun format(timeMs: Long): String = clock.format(Instant.ofEpochMilli(timeMs))

    /** Returns [frame] itself when it is mutable, otherwise a mutable copy with the footer drawn. */
    fun draw(frame: Bitmap, timeMs: Long, progressFraction: Float): Bitmap {
        val bitmap = if (frame.isMutable) frame else frame.copy(Bitmap.Config.ARGB_8888, true)
        val scale = bitmap.width / SERVER_SIZE
        val top = bitmap.height - FOOTER_HEIGHT * scale
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, top, bitmap.width.toFloat(), bitmap.height.toFloat(), background)
        canvas.drawRect(0f, top, bitmap.width * progressFraction, bitmap.height.toFloat(), progress)
        text.textSize = TEXT_SIZE * scale
        val centerY = top + FOOTER_HEIGHT * scale / 2
        val baseline = centerY - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2
        canvas.drawText(format(timeMs), bitmap.width / 2f, baseline, text)
        return bitmap
    }

    private companion object {
        const val SERVER_SIZE = 512f
        const val FOOTER_HEIGHT = 60f
        const val TEXT_SIZE = 50f
    }
}
