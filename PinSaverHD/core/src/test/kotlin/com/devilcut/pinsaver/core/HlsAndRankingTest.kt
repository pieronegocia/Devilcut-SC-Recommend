package com.devilcut.pinsaver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAndRankingTest {

    @Test
    fun parsesMasterPlaylist() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-STREAM-INF:BANDWIDTH=650000,AVERAGE-BANDWIDTH=500000,RESOLUTION=360x640,CODECS="avc1.4d401e,mp4a.40.2",FRAME-RATE=30
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,AVERAGE-BANDWIDTH=3100000,RESOLUTION=1080x1920,CODECS="avc1.640028,mp4a.40.2"
            https://cdn.example.com/1080/index.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,RESOLUTION=1080x1920,URI="iframe.m3u8"
        """.trimIndent()

        val streams = HlsPlaylist.parseMaster(master, "https://v1.pinimg.com/videos/mc/hls/aa/bb/cc/hash.m3u8")!!

        assertEquals(2, streams.size)
        assertEquals("https://v1.pinimg.com/videos/mc/hls/aa/bb/cc/360/index.m3u8", streams[0].uri)
        assertEquals(360, streams[0].width)
        assertEquals("avc1.4d401e,mp4a.40.2", streams[0].codecs)
        assertEquals(1080, streams[1].width)
        assertEquals(1920, streams[1].height)
        assertEquals(3_100_000L, streams[1].averageBandwidth)
    }

    @Test
    fun mediaPlaylistIsNotMaster() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST"
        assertNull(HlsPlaylist.parseMaster(media, "https://x/y.m3u8"))
        assertNull(HlsPlaylist.parseMaster("<html>nope</html>", "https://x/y.m3u8"))
    }

    @Test
    fun derivedMp4AndStems() {
        val hls = "https://v1.pinimg.com/videos/mc/hls/aa/bb/cc/aabbccddeeff00112233.m3u8"
        assertEquals(
            listOf("https://v1.pinimg.com/videos/mc/720p/aa/bb/cc/aabbccddeeff00112233.mp4"),
            VideoUrls.derivedMp4Candidates(hls),
        )
        assertEquals(
            VideoUrls.stemOf(hls),
            VideoUrls.stemOf("https://v1.pinimg.com/videos/mc/720p/aa/bb/cc/aabbccddeeff00112233.mp4"),
        )
        assertEquals(
            VideoUrls.stemOf("https://v1.pinimg.com/videos/iht/expMp4/1111111111aaaaaaaaaa_t1.mp4"),
            VideoUrls.stemOf("https://v1.pinimg.com/videos/iht/expMp4/1111111111aaaaaaaaaa_t4.mp4"),
        )
        assertEquals(720, VideoUrls.shortSideFromUrl("https://v1.pinimg.com/videos/mc/720p/aa/x.mp4"))
    }

    @Test
    fun rankingPrefersResolutionThenMp4ThenH264() {
        val hls1080 = VideoVariant("https://a/hls/x.m3u8", VariantKind.HLS, 1080, 1920)
        val mp4720 = VideoVariant("https://a/720p/x.mp4", VariantKind.MP4, 720, 1280)
        val hevc720 = VideoVariant("https://a/hevc/x.mp4", VariantKind.MP4, 720, 1280, isHevc = true)
        val mp4360 = VideoVariant("https://a/360/x.mp4", VariantKind.MP4, 360, 640)
        val hls360 = VideoVariant("https://a/hls/mobile.m3u8", VariantKind.HLS, 360, 640)

        val ranked = VariantRanking.rankAndPrune(listOf(mp4360, hevc720, hls360, mp4720, hls1080))

        // The 1080p HLS stream beats every MP4, so it stays first; the 360p HLS
        // stream is no better than the MP4s, so it is dropped.
        assertEquals(listOf(hls1080, mp4720, hevc720, mp4360), ranked)
        assertEquals("1080p", ranked.first().qualityLabel)
        assertTrue(ranked.first().isHd)
    }

    @Test
    fun hlsIsDroppedWhenMp4IsAsGood() {
        val hls = VideoVariant("https://a/hls/x.m3u8", VariantKind.HLS, 720, 1280)
        val mp4 = VideoVariant("https://a/720p/x.mp4", VariantKind.MP4, 720, 1280)
        assertEquals(listOf(mp4), VariantRanking.rankAndPrune(listOf(hls, mp4)))
    }

    @Test
    fun scalesToShortSide() {
        assertEquals(720 to 1280, PinterestExtractor.scaleToShortSide(1080, 1920, 720))
        assertEquals(640 to 360, PinterestExtractor.scaleToShortSide(640, 360, 720))
        assertEquals(null to null, PinterestExtractor.scaleToShortSide(null, 1920, 720))
    }

    @Test
    fun fileNames() {
        assertEquals("Receta_facil_de_cafe_123_1080p.mp4", FileNames.build("Receta fácil de café ☕!", "123", "1080p"))
        assertEquals("pinterest_123_clip2_720p.mp4", FileNames.build("🎉🎉", "123", "720p", clipIndex = 1, clipCount = 3))
        assertEquals("pinterest_42.mp4", FileNames.build(null, "42", null))
    }
}
