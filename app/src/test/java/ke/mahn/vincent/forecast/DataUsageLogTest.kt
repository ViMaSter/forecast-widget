package ke.mahn.vincent.forecast

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DataUsageLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Berlin")
    private var now = ZonedDateTime.of(2026, 10, 18, 23, 31, 0, 0, zone).toInstant().toEpochMilli()
    private fun log() = DataUsageLog(folder.root.resolve("usage.csv"), zone) { now }

    @Test
    fun keepsTheLastFiveDownloadsNewestFirst() {
        for (i in 1..7) {
            log().record(i * 1000L, 100)
            now += 5 * 60_000L
        }
        val recent = log().recent()
        assertEquals(listOf(7000L, 6000L, 5000L, 4000L, 3000L), recent.map { it.bytes })
        assertEquals(100, recent.first().zoom)
    }

    @Test
    fun aggregatesHourlyForThePast24Hours() {
        log().record(100, 100)
        now += 10 * 60_000L // 23:41, same hour
        log().record(50, 100)
        now += 60 * 60_000L // 00:41 next day
        log().record(7, 75)

        val hourly = log().hourly()
        assertEquals(24, hourly.size)
        assertEquals(listOf(150L, 7L), hourly.takeLast(2).map { it.bytes })
        assertEquals(157L, hourly.sumOf { it.bytes })

        now += 23 * 60 * 60_000L // 23:41, the 23:00 bucket from yesterday has dropped out
        assertEquals(7L, log().hourly().sumOf { it.bytes })
    }

    @Test
    fun aggregatesDailyForThePast30Days() {
        log().record(100, 100)
        now += 60 * 60_000L // 00:31 on the 19th
        log().record(20, 100)

        val daily = log().daily()
        assertEquals(30, daily.size)
        assertEquals(LocalDate.of(2026, 10, 19), daily.last().date)
        assertEquals(listOf(100L, 20L), daily.takeLast(2).map { it.bytes })

        // 17 Nov: 18 Oct is out of range, 19 Oct is the oldest of the 30 days.
        now = ZonedDateTime.of(2026, 11, 17, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        log().record(1, 100)
        assertEquals(listOf(20L), log().daily().filter { it.bytes > 0 && it.date.monthValue == 10 }.map { it.bytes })
        assertEquals(21L, log().daily().sumOf { it.bytes })
        assertEquals(false, folder.root.resolve("usage.csv").readText().contains("2026-10-18"))
    }
}
