package ke.mahn.vincent.forecast

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class FrameTimelineTest {
    private val start = 1792359900000L // 2026-10-18 23:45 Berlin
    private val timeline = FrameTimeline(start, start + 90 * 60_000L, 91)

    @Test
    fun lerpsOneMinutePerFrame() {
        assertEquals(start, timeline.timeAt(0))
        assertEquals(start + 60_000L, timeline.timeAt(1))
        assertEquals(start + 45 * 60_000L, timeline.timeAt(45))
        assertEquals(start + 90 * 60_000L, timeline.timeAt(90))
    }

    @Test
    fun everyFrameIsExactlyOneMinuteAfterThePrevious() {
        val footer = ForecastFooter(ZoneId.of("Europe/Berlin"))
        for (index in 0..90) {
            assertEquals("frame $index", start + index * 60_000L, timeline.timeAt(index))
            assertEquals(
                "frame $index",
                footer.format(start + index * 60_000L),
                footer.format(timeline.timeAt(index)),
            )
        }
    }

    @Test
    fun progressRunsFromZeroToOne() {
        assertEquals(0f, timeline.progressAt(0))
        assertEquals(0.5f, timeline.progressAt(45))
        assertEquals(1f, timeline.progressAt(90))
        assertEquals(1f, FrameTimeline(start, start, 1).progressAt(0))
    }

    @Test
    fun formatsClockInLocalZone() {
        val footer = ForecastFooter(ZoneId.of("Europe/Berlin"))
        assertEquals("23:45", footer.format(timeline.timeAt(0)))
        assertEquals("01:15", footer.format(timeline.timeAt(90)))
    }
}
