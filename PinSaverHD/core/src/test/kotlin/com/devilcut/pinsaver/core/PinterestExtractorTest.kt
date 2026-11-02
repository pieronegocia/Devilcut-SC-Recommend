package com.devilcut.pinsaver.core

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** End-to-end tests of the network flow against a fake Pinterest. */
class PinterestExtractorTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val route = routes.entries.firstOrNull { path.startsWith(it.key) }
                return route?.value?.invoke(request) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.shutdown()

    private fun apiJson(videoList: String) = """
        {"resource_response": {"status": "success", "code": 0, "data": {
          "id": "1234567", "grid_title": "Mi video",
          "images": {"orig": {"url": "$base/img/orig.jpg"}},
          "videos": {"video_list": {$videoList}}
        }}}
    """

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=480x854,CODECS="avc1.4d401f,mp4a.40.2"
        480.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,AVERAGE-BANDWIDTH=3000000,RESOLUTION=1080x1920,CODECS="avc1.640028,mp4a.40.2"
        1080.m3u8
    """.trimIndent()

    private fun video(size: Long) = { request: RecordedRequest ->
        assertEquals("HEAD", request.method)
        MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Content-Length", size)
    }

    @Test
    fun apiWithHlsAndMp4() = runBlocking {
        routes["/resource/PinResource/get/"] = { request ->
            assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
            assertTrue(request.requestUrl!!.queryParameter("data")!!.contains("\"id\":\"1234567\""))
            MockResponse().setBody(
                apiJson(
                    """
                    "V_HLSV4": {"url": "$base/videos/mc/hls/aa/bb/cc/aabbccddeeff00112233.m3u8", "width": 1080, "height": 1920, "duration": 20000},
                    "V_720P": {"url": "$base/videos/mc/720p/aa/bb/cc/aabbccddeeff00112233.mp4", "width": 720, "height": 1280, "duration": 20000}
                    """,
                ),
            )
        }
        routes["/videos/mc/hls/"] = { MockResponse().setBody(master) }
        routes["/videos/mc/720p/"] = video(5_000_000)

        val pin = PinterestExtractor(http, base).extract("https://www.pinterest.com/pin/1234567/")

        assertEquals("1234567", pin.pinId)
        assertEquals("Mi video", pin.title)
        assertEquals("$base/img/orig.jpg", pin.thumbnailUrl)
        val variants = pin.clips.single().variants
        assertEquals(2, variants.size)
        val hls = variants[0]
        assertEquals(VariantKind.HLS, hls.kind)
        assertEquals("1080p", hls.qualityLabel)
        assertEquals(3_000_000L, hls.bitrate)
        assertEquals(3_000_000L * 20_000 / 8_000, hls.estimatedBytes)
        val mp4 = variants[1]
        assertEquals(VariantKind.MP4, mp4.kind)
        assertEquals("720p", mp4.qualityLabel)
        assertEquals(5_000_000L, mp4.sizeBytes)
        assertFalse(mp4.derived)
    }

    @Test
    fun hlsOnlyFindsTheMp4Twin() = runBlocking {
        routes["/resource/PinResource/get/"] = {
            MockResponse().setBody(
                apiJson(""""V_HLSV4": {"url": "$base/videos/mc/hls/aa/bb/cc/aabbccddeeff00112233.m3u8", "width": 1080, "height": 1920}"""),
            )
        }
        routes["/videos/mc/hls/"] = { MockResponse().setBody(master) }
        routes["/videos/mc/720p/"] = video(4_200_000)

        val variants = PinterestExtractor(http, base).fetchPin("1234567").clips.single().variants

        assertEquals(listOf(VariantKind.HLS, VariantKind.MP4), variants.map { it.kind })
        val twin = variants[1]
        assertTrue(twin.derived)
        assertEquals("$base/videos/mc/720p/aa/bb/cc/aabbccddeeff00112233.mp4", twin.url)
        assertEquals(720, twin.width)
        assertEquals(1280, twin.height)
        assertEquals(4_200_000L, twin.sizeBytes)
    }

    @Test
    fun fallsBackToHtmlWhenApiIsBlocked() = runBlocking {
        routes["/resource/PinResource/get/"] = { MockResponse().setResponseCode(403) }
        routes["/pin/1234567/"] = {
            MockResponse().setBody(
                """
                <html><head><meta property="og:title" content="Video desde HTML"></head><body>
                <script id="__PWS_DATA__" type="application/json">
                {"props": {"initialReduxState": {"pins": {"1234567": {"id": "1234567",
                  "videos": {"video_list": {"V_720P": {"url": "$base/videos/mc/720p/aa/bb/cc/aabbccddeeff00112233.mp4", "width": 720, "height": 1280}}}}}}}}
                </script></body></html>
                """,
            )
        }
        routes["/videos/mc/720p/"] = video(1_000)

        val pin = PinterestExtractor(http, base).fetchPin("1234567")

        assertEquals("Video desde HTML", pin.title)
        assertEquals("720p", pin.clips.single().best.qualityLabel)
    }

    @Test
    fun followsShortLinks() = runBlocking {
        routes["/short/"] = {
            MockResponse().setResponseCode(302).setHeader("Location", "$base/pin/7654321/sent/?invite_code=abc")
        }
        routes["/pin/7654321/"] = { MockResponse().setBody("<html></html>") }

        assertEquals("7654321", PinterestExtractor(http, base).resolveShortLink("$base/short/xyz"))
    }

    @Test
    fun imagePinReportsNoVideo() = runBlocking {
        routes["/resource/PinResource/get/"] = {
            MockResponse().setBody("""{"resource_response": {"status": "success", "data": {"id": "1234567", "title": "Foto"}}}""")
        }
        routes["/pin/1234567/"] = { MockResponse().setBody("<html><body>no video</body></html>") }

        assertFailure(PinterestException.Reason.NO_VIDEO) { PinterestExtractor(http, base).fetchPin("1234567") }
    }

    @Test
    fun missingPinReportsNotFound() = runBlocking {
        routes["/resource/PinResource/get/"] = { MockResponse().setResponseCode(404) }

        assertFailure(PinterestException.Reason.NOT_FOUND) { PinterestExtractor(http, base).fetchPin("1234567") }
    }

    @Test
    fun invalidLink() = runBlocking {
        assertFailure(PinterestException.Reason.INVALID_LINK) { PinterestExtractor(http, base).extract("https://youtube.com/watch?v=x") }
    }

    private suspend fun assertFailure(reason: PinterestException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: PinterestException) {
            assertEquals(reason, e.reason)
        }
    }
}
