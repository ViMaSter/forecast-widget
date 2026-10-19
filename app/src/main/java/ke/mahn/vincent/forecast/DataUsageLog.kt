package ke.mahn.vincent.forecast

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Records the data used by every forecast request, including `304 Not Modified` revalidations. Keeps the last [RECENT_COUNT] downloads,
 * hourly totals for the past 24 hours and daily totals for the past 30 days in a small text file.
 */
class DataUsageLog(
    private val file: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** [notModified] marks a `304` revalidation: only headers were transferred. */
    data class Download(val at: Long, val bytes: Long, val zoom: Int, val notModified: Boolean = false)
    data class Bucket(val start: Long, val bytes: Long)
    data class DayBucket(val date: LocalDate, val bytes: Long)

    private data class State(
        val recent: List<Download>,
        val hourly: Map<Long, Long>,
        val daily: Map<LocalDate, Long>,
    )

    fun record(result: FetchResult, zoom: Int) =
        record(result.transferBytes, zoom, notModified = result is FetchResult.NotModified)

    @Synchronized
    fun record(bytes: Long, zoom: Int, notModified: Boolean = false) {
        val at = now()
        val state = load()
        val hour = hourStart(at)
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        save(
            State(
                recent = (listOf(Download(at, bytes, zoom, notModified)) + state.recent).take(RECENT_COUNT),
                hourly = state.hourly + (hour to (state.hourly[hour] ?: 0) + bytes),
                daily = state.daily + (day to (state.daily[day] ?: 0) + bytes),
            ),
        )
    }

    @Synchronized
    fun recent(): List<Download> = load().recent

    /** 24 buckets, oldest first; the last one is the current hour. */
    @Synchronized
    fun hourly(): List<Bucket> {
        val hourly = load().hourly
        val current = hourStart(now())
        return (HOURS - 1 downTo 0).map { ago ->
            val start = current - ago * HOUR_MS
            Bucket(start, hourly[start] ?: 0)
        }
    }

    /** 30 buckets, oldest first; the last one is today. */
    @Synchronized
    fun daily(): List<DayBucket> {
        val daily = load().daily
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        return (DAYS - 1 downTo 0).map { ago ->
            val date = today.minusDays(ago.toLong())
            DayBucket(date, daily[date] ?: 0)
        }
    }

    private fun hourStart(ms: Long) = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.HOURS).toEpochMilli()

    private fun load(): State {
        val recent = mutableListOf<Download>()
        val hourly = mutableMapOf<Long, Long>()
        val daily = mutableMapOf<LocalDate, Long>()
        if (file.exists()) {
            file.readLines().forEach { line ->
                val parts = line.split(',')
                runCatching {
                    when (parts[0]) {
                        "r" -> recent += Download(parts[1].toLong(), parts[2].toLong(), parts[3].toInt(), parts.getOrNull(4) == "304")
                        "h" -> hourly[parts[1].toLong()] = parts[2].toLong()
                        "d" -> daily[LocalDate.parse(parts[1])] = parts[2].toLong()
                    }
                }
            }
        }
        return State(recent, hourly, daily)
    }

    private fun save(state: State) {
        val oldestHour = hourStart(now()) - (HOURS - 1) * HOUR_MS
        val oldestDay = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate().minusDays(DAYS - 1L)
        val text = buildString {
            state.recent.forEach { appendLine("r,${it.at},${it.bytes},${it.zoom},${if (it.notModified) 304 else 200}") }
            state.hourly.filterKeys { it >= oldestHour }.toSortedMap().forEach { (start, bytes) -> appendLine("h,$start,$bytes") }
            state.daily.filterKeys { !it.isBefore(oldestDay) }.toSortedMap().forEach { (date, bytes) -> appendLine("d,$date,$bytes") }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        const val RECENT_COUNT = 5
        const val HOURS = 24
        const val DAYS = 30
        private const val HOUR_MS = 60 * 60 * 1000L

        fun forContext(context: Context) = DataUsageLog(File(context.applicationContext.filesDir, "data_usage.csv"))
    }
}
