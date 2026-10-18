package ke.mahn.vincent.forecast

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    data object NotModified : FetchResult
    class Video(val bytes: ByteArray, val etag: String?, val stale: Boolean) : FetchResult
}

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
            if (response.code == 304) return FetchResult.NotModified
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val contentType = response.header("Content-Type").orEmpty()
            if (!contentType.startsWith("video/mp4")) throw IOException("Unexpected content type $contentType")
            return FetchResult.Video(
                bytes = response.body.bytes(),
                etag = response.header("ETag"),
                stale = response.header("x-forecast-stale") == "true",
            )
        }
    }

    companion object {
        // Generous read timeout: a request after a cache cutoff waits for server-side rendering.
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
