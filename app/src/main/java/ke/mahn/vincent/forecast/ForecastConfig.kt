package ke.mahn.vincent.forecast

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object ForecastConfig {
    const val BASE_URL = "https://forecast.by.vincent.mahn.ke/forecast"

    const val MIN_ZOOM = 10
    const val MAX_ZOOM = 100

    /** Server regenerates forecasts on 5-minute clock boundaries. */
    const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * Offset after each boundary. During the first 60 s after a boundary the server may still
     * serve the previous forecast, so fetching at +65 s always yields the freshly rendered one.
     */
    const val REFRESH_OFFSET_MS = 65 * 1000L

    /** 2x2 → 100, 3x3 → 75, 4x4 → 50, 5x5 → 25; clamped to the range accepted by the server. */
    fun zoomForCells(cells: Int): Int = (150 - 25 * cells).coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Phone launchers (Pixel, Samsung, …) default to a 4-column home-screen grid. */
    const val GRID_COLUMNS = 4

    /** Launcher rows are slightly taller than columns are wide. */
    const val ROW_TO_COLUMN_RATIO = 1.1

    /**
     * Launchers report widget sizes in dp, not cells, so cells are estimated from the column
     * width (smallest screen width / [GRID_COLUMNS]). Example: a 2×2 Pixel 9 Pro widget is
     * 181×236 dp on a 426 dp wide screen.
     */
    fun cellsFor(sizeDp: Int, cellDp: Double): Int = max(1, (sizeDp / cellDp).roundToInt())

    /** Non-square widgets use their larger side. */
    fun zoomForWidgetSize(widthDp: Int, heightDp: Int, smallestScreenWidthDp: Int): Int {
        val columnDp = smallestScreenWidthDp.toDouble() / GRID_COLUMNS
        val columns = cellsFor(widthDp, columnDp)
        val rows = cellsFor(heightDp, columnDp * ROW_TO_COLUMN_RATIO)
        return zoomForCells(max(columns, rows))
    }

    fun forecastUrl(latitude: Double, longitude: Double, zoom: Int, dark: Boolean = false): String =
        String.format(
            Locale.ROOT, "%s?x=%.4f&y=%.4f&zoom=%d&theme=%s",
            BASE_URL, longitude, latitude, zoom, if (dark) "dark" else "light",
        )

    /** Next 5-minute clock boundary plus [REFRESH_OFFSET_MS], always at least 5 s in the future. */
    fun nextRefreshAt(nowMs: Long): Long {
        val boundary = nowMs - Math.floorMod(nowMs, REFRESH_INTERVAL_MS)
        var next = boundary + REFRESH_OFFSET_MS
        while (next <= nowMs + 5_000) next += REFRESH_INTERVAL_MS
        return next
    }
}
