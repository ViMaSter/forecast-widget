package ke.mahn.vincent.forecast

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    /** Approximate data used by the exchange: request and response headers plus the response body. */
    val transferBytes: Long

    /** A 304 still reports the current [start]/[end], which move every 5 minutes even if the video does not. */
    data class NotModified(
        val start: Long? = null,
        val end: Long? = null,
        override val transferBytes: Long = 0,
    ) : FetchResult
    /** [footerless] videos need the time box drawn by the app, using [start]/[end] (epoch ms) of the first/last frame. */
    class Video(
        val bytes: ByteArray,
        val etag: String?,
        val stale: Boolean,
        val footerless: Boolean = false,
        val alpha: Boolean = false,
        val alphaMask: Boolean = false,
        val frames: Int? = null,
        val start: Long? = null,
        val end: Long? = null,
        override val transferBytes: Long = bytes.size.toLong(),
    ) : FetchResult
}

class ForecastHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

class ForecastFetcher(
    private val client: OkHttpClient = defaultClient,
) {
    @Throws(IOException::class)
    fun fetch(url: String, etag: String?): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "video/mp4")
            .header("User-Agent", "forecast-app/${BuildConfig.VERSION_NAME}")
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        client.newCall(request).execute().use { response ->
            val headerBytes = response.request.headers.byteCount() + response.headers.byteCount()
            if (response.code == 304) {
                return FetchResult.NotModified(
                    start = response.header("x-forecast-start")?.let(::parseInstant),
                    end = response.header("x-forecast-end")?.let(::parseInstant),
                    transferBytes = headerBytes,
                )
            }
            if (!response.isSuccessful) throw ForecastHttpException(response.code)
            val contentType = response.header("Content-Type").orEmpty()
            if (!contentType.startsWith("video/mp4")) throw IOException("Unexpected content type $contentType")
            val bytes = response.body.bytes()
            return FetchResult.Video(
                bytes = bytes,
                etag = response.header("ETag"),
                stale = response.header("x-forecast-stale") == "true",
                footerless = response.header("x-forecast-no-footer") == "1",
                alpha = response.header("x-forecast-alpha") == "1",
                alphaMask = response.header("x-forecast-alpha-mask") == "1",
                frames = response.header("x-forecast-frames")?.toIntOrNull(),
                start = response.header("x-forecast-start")?.let(::parseInstant),
                end = response.header("x-forecast-end")?.let(::parseInstant),
                transferBytes = headerBytes + bytes.size,
            )
        }
    }

    private fun parseInstant(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    companion object {
        // Generous read timeout: a request after a cache cutoff waits for server-side rendering.
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
