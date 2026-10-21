package ke.mahn.vincent.forecast

import android.content.Context
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

        // Always light: the light ramp is what RainPalette maps onto the phone's theme colors.
        val palette = RainPalette.fromTheme(context)
        val store = FrameStore(context)
        val fetcher = ForecastFetcher()
        val usage = DataUsageLog.forContext(context)
        val errors = mutableListOf<String>()
        for (zoom in zooms) {
            try {
                refreshZoom(
                    prefs,
                    store,
                    fetcher,
                    usage,
                    ForecastConfig.forecastUrl(position.first, position.second, zoom),
                    ForecastConfig.alphaMaskUrl(position.first, position.second, zoom),
                    ForecastConfig.legacyForecastUrl(position.first, position.second, zoom),
                    zoom,
                    palette,
                )
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
        prefs: Prefs,
        store: FrameStore,
        fetcher: ForecastFetcher,
        usage: DataUsageLog,
        colorUrl: String,
        maskUrl: String,
        legacyUrl: String,
        zoom: Int,
        palette: RainPalette,
    ) {
        val previous = store.alphaVideos(zoom)
        val colorEtag = previous?.let { prefs.etag(zoom, colorUrl, ALPHA_COLOR_VARIANT) }
        val maskEtag = previous?.let { prefs.etag(zoom, maskUrl, ALPHA_MASK_VARIANT) }

        fun fetchPair(colorValidator: String?, maskValidator: String?): Pair<FetchResult, FetchResult> {
            val mask = fetcher.fetch(maskUrl, maskValidator).also { usage.record(it, zoom) }
            val color = fetcher.fetch(colorUrl, colorValidator).also { usage.record(it, zoom) }
            return color to mask
        }

        suspend fun fetchOrFallback(colorValidator: String?, maskValidator: String?): Pair<FetchResult, FetchResult>? =
            try {
                fetchPair(colorValidator, maskValidator)
            } catch (e: ForecastHttpException) {
                if (e.statusCode != 404) throw e
                Log.w(TAG, "Alpha endpoint unavailable; using the legacy opaque forecast")
                refreshLegacyZoom(prefs, store, fetcher, usage, legacyUrl, zoom)
                null
            }

        var results = fetchOrFallback(colorEtag, maskEtag) ?: return
        var (colorResult, maskResult) = results
        if ((colorResult as? FetchResult.Video)?.stale == true ||
            (maskResult as? FetchResult.Video)?.stale == true
        ) {
            // The paired streams must describe the same render interval.
            delay(STALE_RETRY_DELAY_MS)
            results = fetchOrFallback(null, null) ?: return
            colorResult = results.first
            maskResult = results.second
        }
        val start = when (colorResult) {
            is FetchResult.Video -> colorResult.start
            is FetchResult.NotModified -> colorResult.start
        } ?: throw IOException("Alpha forecast without X-Forecast-Start")
        val end = when (colorResult) {
            is FetchResult.Video -> colorResult.end
            is FetchResult.NotModified -> colorResult.end
        } ?: throw IOException("Alpha forecast without X-Forecast-End")
        if (colorResult is FetchResult.NotModified && maskResult is FetchResult.NotModified) {
            store.rerenderAlpha(zoom, start, end, palette)
            return
        }

        val colorBytes = when (colorResult) {
            is FetchResult.Video -> {
                if (!colorResult.alpha || colorResult.alphaMask) {
                    throw IOException("Forecast color endpoint did not return an alpha rain video")
                }
                colorResult.bytes
            }
            is FetchResult.NotModified -> previous?.color
                ?: throw IOException("Color video returned 304 without a cached copy")
        }
        val maskBytes = when (maskResult) {
            is FetchResult.Video -> {
                if (!maskResult.alpha || !maskResult.alphaMask) {
                    throw IOException("Forecast alpha endpoint did not return an opacity mask")
                }
                maskResult.bytes
            }
            is FetchResult.NotModified -> previous?.mask
                ?: throw IOException("Alpha video returned 304 without a cached copy")
        }
        val colorVideo = colorResult as? FetchResult.Video
        val maskVideo = maskResult as? FetchResult.Video
        if (colorVideo != null && maskVideo != null) {
            if (colorVideo.frames != null && maskVideo.frames != null &&
                colorVideo.frames != maskVideo.frames
            ) {
                throw IOException("Rain and alpha videos have different frame counts")
            }
            if (colorVideo.start != null && maskVideo.start != null &&
                colorVideo.start != maskVideo.start
            ) {
                throw IOException("Rain and alpha videos have different start times")
            }
            if (colorVideo.end != null && maskVideo.end != null && colorVideo.end != maskVideo.end) {
                throw IOException("Rain and alpha videos have different end times")
            }
        }
        store.saveAlphaVideos(zoom, colorBytes, maskBytes, start to end, palette)
        colorVideo?.let { prefs.setEtag(zoom, colorUrl, it.etag, ALPHA_COLOR_VARIANT) }
        maskVideo?.let { prefs.setEtag(zoom, maskUrl, it.etag, ALPHA_MASK_VARIANT) }
    }

    private suspend fun refreshLegacyZoom(
        prefs: Prefs,
        store: FrameStore,
        fetcher: ForecastFetcher,
        usage: DataUsageLog,
        url: String,
        zoom: Int,
    ) {
        val etag = if (store.hasFrames(zoom) && store.canRedrawFooter(zoom)) prefs.etag(zoom, url) else null
        var result = fetcher.fetch(url, etag)
        usage.record(result, zoom)
        if (result is FetchResult.Video && result.stale) {
            delay(STALE_RETRY_DELAY_MS)
            result = fetcher.fetch(url, null)
            usage.record(result, zoom)
        }
        if (result is FetchResult.NotModified && result.start != null) {
            store.redrawFooter(zoom, result.start, result.end ?: result.start)
        }
        if (result is FetchResult.Video) {
            val footer = if (result.footerless) {
                val start = result.start ?: throw IOException("Footerless forecast without X-Forecast-Start")
                start to (result.end ?: start)
            } else null
            store.saveVideo(zoom, result.bytes, footer)
            prefs.setEtag(zoom, url, result.etag)
        }
    }

    private companion object {
        const val TAG = "RefreshWorker"
        const val STALE_RETRY_DELAY_MS = 20_000L
        const val ALPHA_COLOR_VARIANT = "alpha-color"
        const val ALPHA_MASK_VARIANT = "alpha-mask"
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
