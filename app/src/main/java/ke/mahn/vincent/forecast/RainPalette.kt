package ke.mahn.vincent.forecast

import android.content.Context
import kotlin.math.roundToInt

/**
 * Recolors WetterOnline's light-theme rain ramp with the phone's Material You primary palette.
 * Each pixel is projected onto [LIGHT_RAMP] to get an intensity from 0 (weakest) to 1 (strongest),
 * which then picks a color between the lightest and darkest of [tones].
 */
class RainPalette(private val tones: IntArray) {
    init {
        require(tones.size >= 2) { "At least two tones are required" }
    }

    /** Stored next to rendered frames; frames are re-rendered when it changes. */
    val key: String = tones.joinToString(",") { "%06X".format(it and 0xffffff) }

    /** Maps a straight (not premultiplied) RGB color to a theme color; alpha is not touched. */
    fun recolor(rgb: Int): Int = colorAt((intensityLut[lutIndex(rgb)].toInt() and 0xff) / 255f)

    /**
     * The alpha color video is flattened onto black, so edge pixels are premultiplied by [alpha].
     * Returns an ARGB pixel with [alpha] as opacity, or fully transparent for alpha 0.
     */
    fun recolorPremultiplied(rgb: Int, alpha: Int): Int {
        if (alpha == 0) return 0
        val straight = if (alpha >= 255) rgb else {
            fun channel(shift: Int) = (((rgb shr shift) and 0xff) * 255 / alpha).coerceAtMost(255)
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return (alpha shl 24) or (recolor(straight) and 0xffffff)
    }

    internal fun colorAt(intensity: Float): Int {
        val position = intensity.coerceIn(0f, 1f) * (tones.size - 1)
        val lower = position.toInt().coerceAtMost(tones.size - 2)
        return lerp(tones[lower], tones[lower + 1], position - lower)
    }

    companion object {
        /**
         * Light-theme ramp from weak to strong rain as served by `/forecast?theme=light&alpha=1`.
         * The final magenta is WetterOnline's hail level.
         */
        val LIGHT_RAMP = intArrayOf(
            0xA8FCFC.toInt(), 0x50D0FC, 0x30ACFC, 0x2090F4, 0x147CDC, 0x0C6CC8, 0x903090, 0xEA33F7.toInt(),
        )

        /** Material You primary tones, light to dark, so weak rain is light and heavy rain is dark. */
        val ACCENT_TONES = intArrayOf(
            android.R.color.system_accent1_100,
            android.R.color.system_accent1_200,
            android.R.color.system_accent1_300,
            android.R.color.system_accent1_400,
            android.R.color.system_accent1_500,
            android.R.color.system_accent1_600,
            android.R.color.system_accent1_700,
            android.R.color.system_accent1_800,
        )

        fun fromTheme(context: Context): RainPalette =
            RainPalette(IntArray(ACCENT_TONES.size) { context.getColor(ACCENT_TONES[it]) })

        private const val LUT_BITS = 5
        private const val LUT_SIZE = 1 shl LUT_BITS

        private fun lutIndex(rgb: Int): Int {
            val shift = 8 - LUT_BITS
            val r = (rgb shr 16 and 0xff) shr shift
            val g = (rgb shr 8 and 0xff) shr shift
            val b = (rgb and 0xff) shr shift
            return (r shl (2 * LUT_BITS)) or (g shl LUT_BITS) or b
        }

        /** Intensity (0..255) of the nearest point on the ramp polyline for every quantized RGB cell. */
        private val intensityLut: ByteArray by lazy {
            val lut = ByteArray(LUT_SIZE * LUT_SIZE * LUT_SIZE)
            val step = 256f / LUT_SIZE
            for (r in 0 until LUT_SIZE) for (g in 0 until LUT_SIZE) for (b in 0 until LUT_SIZE) {
                val t = intensity((r + 0.5f) * step, (g + 0.5f) * step, (b + 0.5f) * step)
                lut[(r shl (2 * LUT_BITS)) or (g shl LUT_BITS) or b] = (t * 255).roundToInt().toByte()
            }
            lut
        }

        internal fun intensity(r: Float, g: Float, b: Float): Float {
            var best = Float.MAX_VALUE
            var bestT = 0f
            val segments = LIGHT_RAMP.size - 1
            for (segment in 0 until segments) {
                val from = LIGHT_RAMP[segment]
                val to = LIGHT_RAMP[segment + 1]
                val r0 = (from shr 16 and 0xff).toFloat()
                val g0 = (from shr 8 and 0xff).toFloat()
                val b0 = (from and 0xff).toFloat()
                val dr = (to shr 16 and 0xff) - r0
                val dg = (to shr 8 and 0xff) - g0
                val db = (to and 0xff) - b0
                val length = dr * dr + dg * dg + db * db
                val u = (((r - r0) * dr + (g - g0) * dg + (b - b0) * db) / length).coerceIn(0f, 1f)
                val er = r0 + u * dr - r
                val eg = g0 + u * dg - g
                val eb = b0 + u * db - b
                val distance = er * er + eg * eg + eb * eb
                if (distance < best) {
                    best = distance
                    bestT = (segment + u) / segments
                }
            }
            return bestT
        }

        private fun lerp(from: Int, to: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val a = from shr shift and 0xff
                val b = to shr shift and 0xff
                return (a + (b - a) * amount).roundToInt().coerceIn(0, 255)
            }
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
