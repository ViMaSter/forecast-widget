package ke.mahn.vincent.forecast

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.io.IOException

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = Prefs(context)
        val zooms = WidgetRenderer.activeZooms(context)
        if (zooms.isEmpty()) return Result.success()
        prefs.lastAttemptAt = System.currentTimeMillis()

        val position = LocationResolver(context).resolve()
        if (position == null) {
            prefs.lastError = context.getString(R.string.error_no_location)
            WidgetRenderer.renderAll(context)
            return Result.success()
        }

        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val store = FrameStore(context)
        val fetcher = ForecastFetcher()
        val usage = DataUsageLog.forContext(context)
        val errors = mutableListOf<String>()
        for (zoom in zooms) {
            try {
                refreshZoom(prefs, store, fetcher, usage, ForecastConfig.forecastUrl(position.first, position.second, zoom, dark), zoom)
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed for zoom $zoom", e)
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        store.retainZooms(zooms)
        prefs.retainEtags(zooms)

        if (errors.isEmpty()) {
            prefs.lastError = null
            prefs.lastSuccessAt = System.currentTimeMillis()
        } else {
            prefs.lastError = context.getString(R.string.error_refresh, errors.first())
        }
        WidgetRenderer.renderAll(context)
        // The next alarm retries anyway; returning success avoids piling up WorkManager backoff.
        return Result.success()
    }

    private suspend fun refreshZoom(
        prefs: Prefs, store: FrameStore, fetcher: ForecastFetcher, usage: DataUsageLog, url: String, zoom: Int,
    ) {
        // Only revalidate when a 304 can be handled, i.e. the footer can be redrawn for the new time range.
        val etag = if (store.hasFrames(zoom) && store.canRedrawFooter(zoom)) prefs.etag(zoom, url) else null
        var result = fetcher.fetch(url, etag)
        if (result is FetchResult.Video) usage.record(result.bytes.size.toLong(), zoom)
        if (result is FetchResult.Video && result.stale) {
            // Server is still rendering this interval; give it a moment and ask again.
            delay(STALE_RETRY_DELAY_MS)
            result = fetcher.fetch(url, null)
            if (result is FetchResult.Video) usage.record(result.bytes.size.toLong(), zoom)
        }
        if (result is FetchResult.NotModified && result.start != null) {
            // Same footerless video, but its frames now stand for a later time range.
            store.redrawFooter(zoom, result.start, result.end ?: result.start)
        }
        if (result is FetchResult.Video) {
            val video = result
            val footer = if (video.footerless) {
                val start = video.start ?: throw IOException("Footerless forecast without X-Forecast-Start")
                start to (video.end ?: start)
            } else null
            store.saveVideo(zoom, video.bytes, footer)
            prefs.setEtag(zoom, url, result.etag)
        }
    }

    private companion object {
        const val TAG = "RefreshWorker"
        const val STALE_RETRY_DELAY_MS = 20_000L
    }
}

class SafetyNetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (WidgetRenderer.widgetIds(context).isEmpty()) return Result.success()
        RefreshScheduler.ensureScheduled(context)
        val overdue = System.currentTimeMillis() - Prefs(context).lastAttemptAt > 2 * ForecastConfig.REFRESH_INTERVAL_MS
        if (overdue) RefreshScheduler.refreshNow(context)
        return Result.success()
    }
}
