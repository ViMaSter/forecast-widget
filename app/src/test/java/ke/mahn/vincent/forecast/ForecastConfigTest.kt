package ke.mahn.vincent.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneOffset

class ForecastConfigTest {

    @Test
    fun zoomForCellsMatchesSpec() {
        assertEquals(100, ForecastConfig.zoomForCells(1))
        assertEquals(100, ForecastConfig.zoomForCells(2))
        assertEquals(75, ForecastConfig.zoomForCells(3))
        assertEquals(50, ForecastConfig.zoomForCells(4))
        assertEquals(25, ForecastConfig.zoomForCells(5))
        assertEquals(10, ForecastConfig.zoomForCells(6))
        assertEquals(10, ForecastConfig.zoomForCells(12))
    }

    @Test
    fun pixelLauncherSizesMapToExpectedCells() {
        val pixel9Pro = 426 // smallest screen width in dp
        // Reported portrait sizes (min width × max height) from Pixel Launcher.
        assertEquals(100, ForecastConfig.zoomForWidgetSize(181, 236, pixel9Pro)) // 2×2
        assertEquals(75, ForecastConfig.zoomForWidgetSize(288, 354, pixel9Pro)) // 3×3
        assertEquals(50, ForecastConfig.zoomForWidgetSize(394, 472, pixel9Pro)) // 4×4
        assertEquals(100, ForecastConfig.zoomForWidgetSize(75, 118, pixel9Pro)) // 1×1
    }

    @Test
    fun nonSquareWidgetsUseLargerSide() {
        val screen = 411
        assertEquals(50, ForecastConfig.zoomForWidgetSize(widthDp = 380, heightDp = 113, smallestScreenWidthDp = screen)) // 4×1
        assertEquals(75, ForecastConfig.zoomForWidgetSize(widthDp = 180, heightDp = 340, smallestScreenWidthDp = screen)) // 2×3
    }

    @Test
    fun forecastUrlPutsLongitudeInXAndLatitudeInY() {
        assertEquals(
            "https://forecast.by.vincent.mahn.ke/forecast?x=9.9500&y=53.5586&zoom=100&theme=light",
            ForecastConfig.forecastUrl(latitude = 53.5585528, longitude = 9.9500347, zoom = 100),
        )
        assertEquals(
            "https://forecast.by.vincent.mahn.ke/forecast?x=9.9500&y=53.5586&zoom=50&theme=dark",
            ForecastConfig.forecastUrl(latitude = 53.5585528, longitude = 9.9500347, zoom = 50, dark = true),
        )
    }

    @Test
    fun nextRefreshIsAlignedToFiveMinuteBoundaryPlusOffset() {
        fun at(h: Int, m: Int, s: Int) = ZonedDateTime.of(2026, 10, 18, h, m, s, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

        assertEquals(at(12, 1, 5), ForecastConfig.nextRefreshAt(at(12, 0, 0)))
        assertEquals(at(12, 6, 5), ForecastConfig.nextRefreshAt(at(12, 1, 5)))
        assertEquals(at(12, 6, 5), ForecastConfig.nextRefreshAt(at(12, 3, 30)))
        // Within 5 s of the target the next slot is used to avoid double firing.
        assertEquals(at(12, 11, 5), ForecastConfig.nextRefreshAt(at(12, 6, 1)))
        assertEquals(at(13, 1, 5), ForecastConfig.nextRefreshAt(at(12, 59, 59)))
    }

    @Test
    fun nextRefreshIsAlwaysWithinOneInterval() {
        val start = 1_790_000_000_000L
        for (offset in 0 until 600_000 step 997) {
            val now = start + offset
            val next = ForecastConfig.nextRefreshAt(now)
            assertTrue(next > now + 5_000)
            assertTrue(next <= now + ForecastConfig.REFRESH_INTERVAL_MS + 5_000)
            assertEquals(ForecastConfig.REFRESH_OFFSET_MS, Math.floorMod(next, ForecastConfig.REFRESH_INTERVAL_MS))
        }
    }
}
