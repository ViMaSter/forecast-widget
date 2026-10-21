package ke.mahn.vincent.forecast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaFooterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = FrameStore(instrumentation.targetContext)
    private val zoom = 999
    private val start = 1_792_562_400_000L
    private val end = start + 90 * 60_000L
    private val palette = RainPalette(intArrayOf(0xFFFFDBC9.toInt(), 0xFF4C270F.toInt()))

    @After
    fun cleanUp() {
        java.io.File(FrameStore.rootDir(instrumentation.targetContext), "z$zoom").deleteRecursively()
    }

    @Test
    fun fullyTransparentForecastStaysInvisibleEvenAfterTimeRangeChanges() {
        assertEquals(3, store.saveAlphaVideos(zoom, video("color"), video("dry"), start to end, palette))
        assertAllFramesTransparent()
        assertFalse(store.rerenderAlpha(zoom, start, end, palette))
        assertTrue(store.rerenderAlpha(zoom, start + 300_000L, end + 300_000L, palette))
        assertAllFramesTransparent()
    }

    @Test
    fun rainOnlyInLastFrameAddsFooterToEveryFrameAcrossFullRange() {
        assertEquals(3, store.saveAlphaVideos(zoom, video("color"), video("late-rain"), start to end, palette))
        assertDryFrameFooter(0, start, 0f)
        assertDryFrameFooter(1, start + 45 * 60_000L, 0.5f)
        val frames = store.frameUris(zoom)
        val first = readFrame(0)
        val last = readFrame(2)
        try {
            assertEquals(0, first.getPixel(0, 0) ushr 24)
            assertTrue(last.getPixel(0, 0) ushr 24 > 0)
            assertTrue(last.getPixel(0, last.height - 1) ushr 24 > 0)
            assertNotEquals(first.getPixel(0, first.height - 1), last.getPixel(0, last.height - 1))
        } finally {
            first.recycle()
            last.recycle()
        }
        assertFalse(store.rerenderAlpha(zoom, start, end, palette))
        assertEquals(frames, store.frameUris(zoom))
        assertTrue(store.rerenderAlpha(zoom, start + 300_000L, end + 300_000L, palette))
        assertDryFrameFooter(0, start + 300_000L, 0f)
        assertDryFrameFooter(1, start + 50 * 60_000L, 0.5f)
    }

    @Test
    fun themePaletteChangeRecolorsRainButKeepsOpacity() {
        store.saveAlphaVideos(zoom, video("color"), video("late-rain"), start to end, palette)
        assertFalse(store.hasStalePalette(palette, setOf(zoom)))
        val before = readFrame(2).let { frame -> frame.getPixel(0, 0).also { frame.recycle() } }
        val blue = RainPalette(intArrayOf(0xFFD6E3FF.toInt(), 0xFF00315C.toInt()))
        assertTrue(store.hasStalePalette(blue, setOf(zoom)))
        assertTrue(store.rerenderAlpha(zoom, start, end, blue))
        assertFalse(store.hasStalePalette(blue, setOf(zoom)))
        val after = readFrame(2).let { frame -> frame.getPixel(0, 0).also { frame.recycle() } }
        assertEquals(before ushr 24, after ushr 24)
        assertNotEquals(before and 0xffffff, after and 0xffffff)
        assertTrue("Recolored rain should be bluish", (after and 0xff) > (after shr 16 and 0xff))
        assertTrue("Original rain should use the warm palette", (before shr 16 and 0xff) > (before and 0xff))
    }

    private fun video(name: String): ByteArray =
        instrumentation.context.assets.open("$name.mp4").use { it.readBytes() }

    private fun readFrame(index: Int): Bitmap =
        instrumentation.targetContext.contentResolver.openInputStream(store.frameUris(zoom)[index]).use {
            requireNotNull(BitmapFactory.decodeStream(it))
        }

    private fun assertAllFramesTransparent() {
        store.frameUris(zoom).indices.forEach { index ->
            val frame = readFrame(index)
            try {
                val pixels = IntArray(frame.width * frame.height)
                frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                assertTrue("Frame $index should be completely transparent", pixels.all { it ushr 24 == 0 })
            } finally {
                frame.recycle()
            }
        }
    }

    private fun assertDryFrameFooter(index: Int, time: Long, progress: Float) {
        val actual = readFrame(index)
        val expected = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
        try {
            ForecastFooter().draw(expected, time, progress)
            assertTrue("Dry frame $index should have the expected time and progress", expected.sameAs(actual))
        } finally {
            actual.recycle()
            expected.recycle()
        }
    }
}
