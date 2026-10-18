package ke.mahn.vincent.forecast

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ForecastFetcherTest {
    private val server = MockWebServer()
    private val fetcher = ForecastFetcher()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun returnsVideoWithEtagAndStaleFlag() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "video/mp4")
                .addHeader("ETag", "\"abc\"")
                .addHeader("x-forecast-stale", "true")
                .body("ftyp")
                .build(),
        )
        val result = fetcher.fetch(server.url("/forecast").toString(), null) as FetchResult.Video
        assertArrayEquals("ftyp".toByteArray(), result.bytes)
        assertEquals("\"abc\"", result.etag)
        assertTrue(result.stale)
        assertEquals(null, server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun sendsEtagAndHandlesNotModified() {
        server.enqueue(
            MockResponse.Builder().code(304)
                .addHeader("X-Forecast-Start", "2026-10-18T21:45:00.000Z")
                .addHeader("X-Forecast-End", "2026-10-18T23:15:00.000Z")
                .build(),
        )
        val result = fetcher.fetch(server.url("/forecast").toString(), "\"abc\"")
        assertEquals(FetchResult.NotModified(1792359900000L, 1792365300000L), result)
        assertEquals("\"abc\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun freshResponseIsNotStale() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "video/mp4").body("ftyp").build())
        val result = fetcher.fetch(server.url("/forecast").toString(), null) as FetchResult.Video
        assertFalse(result.stale)
    }

    @Test
    fun readsFooterlessTimeline() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "video/mp4")
                .addHeader("X-Forecast-No-Footer", "1")
                .addHeader("X-Forecast-Start", "2026-10-18T21:45:00.000Z")
                .addHeader("X-Forecast-End", "2026-10-18T23:15:00.000Z")
                .body("ftyp")
                .build(),
        )
        val result = fetcher.fetch(server.url("/forecast").toString(), null) as FetchResult.Video
        assertTrue(result.footerless)
        assertEquals(1792359900000L, result.start)
        assertEquals(1792365300000L, result.end)
    }

    @Test
    fun footerIsServerRenderedByDefault() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "video/mp4").body("ftyp").build())
        val result = fetcher.fetch(server.url("/forecast").toString(), null) as FetchResult.Video
        assertFalse(result.footerless)
        assertEquals(null, result.start)
    }

    @Test(expected = IOException::class)
    fun serverErrorThrows() {
        server.enqueue(MockResponse.Builder().code(502).build())
        fetcher.fetch(server.url("/forecast").toString(), null)
    }

    @Test(expected = IOException::class)
    fun gifThrows() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/gif").body("GIF89a").build())
        fetcher.fetch(server.url("/forecast").toString(), null)
    }

    @Test(expected = IOException::class)
    fun unexpectedTypeThrows() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/plain").body("nope").build())
        fetcher.fetch(server.url("/forecast").toString(), null)
    }
}
