package com.devilcut.pinsaver.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoJsonScannerTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun classicVideoPin() {
        val data = parse(
            """
            {
              "id": "111222333",
              "title": "",
              "grid_title": "Tacos al pastor",
              "images": {"orig": {"url": "https://i.pinimg.com/originals/aa/bb/cc/img.jpg"}},
              "videos": {
                "id": "999",
                "video_list": {
                  "V_HLSV4": {"url": "https://v1.pinimg.com/videos/mc/hls/aa/bb/cc/aabbccddeeff00112233.m3u8", "width": 1080, "height": 1920, "duration": 15000, "thumbnail": "https://i.pinimg.com/videos/thumbnails/originals/aa.jpg"},
                  "V_720P": {"url": "https://v1.pinimg.com/videos/mc/720p/aa/bb/cc/aabbccddeeff00112233.mp4", "width": 720, "height": 1280, "duration": 15000},
                  "V_HLSV3_MOBILE": {"url": "https://v1.pinimg.com/videos/mc/hls/aa/bb/cc/aabbccddeeff00112233_mobile.m3u8", "width": 240, "height": 426}
                }
              }
            }
            """,
        )
        val result = VideoJsonScanner.scan(listOf(data), "111222333")

        assertTrue(result.matchedPin)
        assertEquals("Tacos al pastor", result.title)
        assertEquals("https://i.pinimg.com/originals/aa/bb/cc/img.jpg", result.thumbnail)
        assertEquals(1, result.clips.size)
        val clip = result.clips.single()
        assertEquals(3, clip.variants.size)
        assertEquals(15000L, clip.durationMs)
        assertEquals("https://i.pinimg.com/videos/thumbnails/originals/aa.jpg", clip.thumbnail)
        assertEquals(setOf("V_HLSV4", "V_720P", "V_HLSV3_MOBILE"), clip.variants.map { it.formatId }.toSet())
    }

    @Test
    fun multiPageIdeaPinGivesOneClipPerPage() {
        val data = parse(
            """
            {
              "id": "555",
              "videos": null,
              "story_pin_data": {
                "pages": [
                  {"blocks": [{"block_type": 3, "video": {"video_list": {
                    "V_EXP7": {"url": "https://v1.pinimg.com/videos/iht/expMp4/1111111111aaaaaaaaaa_t4.mp4", "width": 1080, "height": 1920, "duration": 8000},
                    "V_EXP4": {"url": "https://v1.pinimg.com/videos/iht/expMp4/1111111111aaaaaaaaaa_t1.mp4", "width": 360, "height": 640}
                  }}}]},
                  {"blocks": [{"block_type": 3, "video": {"video_list": {
                    "V_EXP7": {"url": "https://v1.pinimg.com/videos/iht/expMp4/2222222222bbbbbbbbbb_t4.mp4", "width": 1080, "height": 1920, "duration": 6000}
                  }}}]}
                ]
              }
            }
            """,
        )
        val result = VideoJsonScanner.scan(listOf(data), "555")

        assertEquals(2, result.clips.size)
        assertEquals(2, result.clips[0].variants.size)
        assertEquals(8000L, result.clips[0].durationMs)
        assertEquals(1, result.clips[1].variants.size)
        assertEquals(6000L, result.clips[1].durationMs)
    }

    @Test
    fun relayDataIgnoresRelatedPinsAndMergesDuplicates() {
        val main = """
            {"entityId": "777", "id": "UGluOjc3Nw==", "title": "Main video",
             "videos": {"videoList720P": {"v720P": {"url": "https://v1.pinimg.com/videos/mc/720p/12/34/56/123456abcdef7890aaaa.mp4", "width": 720, "height": 1280}}},
             "videoListHLS": {"vHLSV4": {"url": "https://v1.pinimg.com/videos/mc/hls/12/34/56/123456abcdef7890aaaa.m3u8", "width": 1080, "height": 1920}}}
        """
        val related = """
            {"entityId": "888", "title": "Other video",
             "videos": {"videoList720P": {"v720P": {"url": "https://v1.pinimg.com/videos/mc/720p/ff/ff/ff/ffffffffffffffffffff.mp4", "width": 720, "height": 1280}}}}
        """
        val page = parse("""{"data": {"v3GetPinQuery": {"data": $main}, "related": [$related, $related], "again": $main}}""")

        val result = VideoJsonScanner.scan(listOf(page), "777")

        assertTrue(result.matchedPin)
        assertEquals("Main video", result.title)
        assertEquals(1, result.clips.size)
        val urls = result.clips.single().variants.map { it.url }
        assertEquals(2, urls.size)
        assertFalse(urls.any { "ffffffff" in it })
    }

    @Test
    fun noVideo() {
        val data = parse("""{"id": "1", "title": "Just an image", "images": {"orig": {"url": "https://i.pinimg.com/originals/x.jpg"}}}""")
        val result = VideoJsonScanner.scan(listOf(data), "1")
        assertTrue(result.clips.isEmpty())
        assertEquals("Just an image", result.title)
    }

    @Test
    fun htmlPageWithEmbeddedJson() {
        val html = """
            <html><head>
            <meta property="og:title" content="Postre fácil &amp; r&#225;pido | Pinterest">
            <meta content="https://i.pinimg.com/736x/aa/og.jpg" property="og:image">
            <link rel="canonical" href="https://www.pinterest.com/pin/4242424242/">
            </head><body>
            <script>window.foo = 1;</script>
            <script data-relay-response="true" type="application/json">
            {"response": {"data": {"v3GetPinQuery": {"data": {"entityId": "4242424242",
              "videos": {"videoList": {"v720P": {"url": "https://v1.pinimg.com/videos/mc/720p/de/ad/be/deadbeefdeadbeef0001.mp4", "width": 720, "height": 1280, "duration": 9000}}}}}}}}
            </script>
            <script data-relay-response="true" type="application/json">
            {"response": {"data": {"related": [{"entityId": "1", "videos": {"videoList": {"v720P": {"url": "https://v1.pinimg.com/videos/mc/720p/00/00/00/00000000000000000000.mp4"}}}}]}}}
            </script>
            </body></html>
        """
        val found = HtmlPinParser.parse(html, "4242424242")
        assertEquals(1, found.clips.size)
        assertEquals("https://v1.pinimg.com/videos/mc/720p/de/ad/be/deadbeefdeadbeef0001.mp4", found.clips.single().variants.single().url)
        assertEquals("Postre fácil & rápido", found.title)
        assertEquals("https://i.pinimg.com/736x/aa/og.jpg", found.thumbnail)
        assertEquals("4242424242", HtmlPinParser.canonicalPinId(html))
    }

    @Test
    fun htmlLastResortRegex() {
        val html = """<html><body><div data-x="{&quot;u&quot;:&quot;https:\/\/v1.pinimg.com\/videos\/mc\/720p\/ab\/cd\/ef\/abcdef0123456789abcd.mp4&quot;}"></div></body></html>"""
        val found = HtmlPinParser.parse(html, "1")
        assertEquals(
            "https://v1.pinimg.com/videos/mc/720p/ab/cd/ef/abcdef0123456789abcd.mp4",
            found.clips.single().variants.single().url,
        )
    }
}
