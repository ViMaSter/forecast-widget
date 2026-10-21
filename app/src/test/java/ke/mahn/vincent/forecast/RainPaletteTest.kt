package ke.mahn.vincent.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainPaletteTest {
    private val tones = intArrayOf(0xFFFFDBC9.toInt(), 0xFF9E6B4F.toInt(), 0xFF4C270F.toInt())
    private val palette = RainPalette(tones)

    @Test
    fun weakestRainUsesLightestToneAndHailUsesDarkest() {
        assertEquals(tones.first(), palette.recolor(0xA8FCFC))
        assertEquals(tones.last(), palette.recolor(0xEA33F7))
    }

    @Test
    fun intensityIncreasesAlongTheLightRamp() {
        val intensities = RainPalette.LIGHT_RAMP.map {
            RainPalette.intensity((it shr 16 and 0xff).toFloat(), (it shr 8 and 0xff).toFloat(), (it and 0xff).toFloat())
        }
        assertEquals(0f, intensities.first(), 0.001f)
        assertEquals(1f, intensities.last(), 0.001f)
        intensities.zipWithNext().forEach { (weaker, stronger) -> assertTrue(stronger > weaker) }
    }

    @Test
    fun colorsInBetweenAreInterpolated() {
        assertEquals(tones[1], palette.colorAt(0.5f))
        assertEquals(0xFFCFA38C.toInt(), palette.colorAt(0.25f))
    }

    @Test
    fun premultipliedEdgesAreRestoredBeforeMapping() {
        // 20 %-opaque weakest rain flattened onto black.
        val edge = palette.recolorPremultiplied(0x223232, 51)
        assertEquals(51, edge ushr 24)
        assertEquals(tones.first() and 0xffffff, edge and 0xffffff)
        assertEquals(0, palette.recolorPremultiplied(0xffffff, 0))
    }

    @Test
    fun keyChangesWithTheme() {
        assertEquals("FFDBC9,9E6B4F,4C270F", palette.key)
        assertTrue(palette.key != RainPalette(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).key)
    }
}
