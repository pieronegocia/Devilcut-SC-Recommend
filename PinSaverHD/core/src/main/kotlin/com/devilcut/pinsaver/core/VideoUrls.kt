package com.devilcut.pinsaver.core

internal object VideoUrls {
    private val VIDEO_EXTENSION = Regex("""\.(mp4|m3u8)$""", RegexOption.IGNORE_CASE)
    private val RESOLUTION_FOLDER = Regex("""/(\d{3,4})p/""")
    private val STEM_SUFFIX = Regex("""(_t\d+|_mobile|_\d{3,4}[pw]?)$""")

    /** Pinterest video URLs as they appear in raw HTML. Used only as a last resort. */
    val LOOSE_PINIMG = Regex(
        """https?://[a-z0-9-]+\.pinimg\.com/videos/[A-Za-z0-9/_.\-]+?\.(?:mp4|m3u8)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param strict when true the URL must also live under a `/videos/` folder.
     * Strict mode is used for loose strings, where there is no surrounding
     * structure that tells us it is a video.
     */
    fun isVideoUrl(value: String, strict: Boolean): Boolean {
        if (!value.startsWith("https://", ignoreCase = true) && !value.startsWith("http://", ignoreCase = true)) {
            return false
        }
        if (value.length > 4096 || value.any { it.isWhitespace() }) return false
        val path = withoutQuery(value)
        if (!VIDEO_EXTENSION.containsMatchIn(path)) return false
        return !strict || path.contains("/videos/")
    }

    fun isHls(url: String): Boolean = withoutQuery(url).endsWith(".m3u8", ignoreCase = true)

    fun isHevc(url: String, formatId: String?): Boolean =
        formatId?.contains("HEVC", ignoreCase = true) == true ||
            url.contains("hevc", ignoreCase = true) ||
            url.contains("h265", ignoreCase = true)

    /** 720 for `.../videos/mc/720p/aa/bb/cc/hash.mp4`. */
    fun shortSideFromUrl(url: String): Int? =
        RESOLUTION_FOLDER.find(withoutQuery(url))?.groupValues?.get(1)?.toIntOrNull()

    /**
     * An identifier shared by all qualities of the same video. Pinterest names
     * every rendition after the same hash, e.g.
     * `/videos/mc/hls/aa/bb/cc/<hash>.m3u8` and `/videos/mc/720p/aa/bb/cc/<hash>.mp4`.
     */
    fun stemOf(url: String): String {
        val path = withoutQuery(url)
        val name = path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val stem = name.replace(STEM_SUFFIX, "")
        return if (stem.length >= 10) stem else path.substringBeforeLast('.').lowercase()
    }

    /**
     * Pinterest keeps an MP4 copy next to most HLS streams:
     * `/videos/mc/hls/…/hash.m3u8` → `/videos/mc/720p/…/hash.mp4`.
     */
    fun derivedMp4Candidates(hlsUrl: String): List<String> {
        val path = withoutQuery(hlsUrl)
        if (!path.contains("/hls/") || !isHls(path)) return emptyList()
        val mp4 = path.replace("/hls/", "/720p/").replace(Regex("""\.m3u8$""", RegexOption.IGNORE_CASE), ".mp4")
        val withoutMobile = mp4.replace(Regex("""_mobile\.mp4$"""), ".mp4")
        return listOf(mp4, withoutMobile).distinct()
    }

    private fun withoutQuery(url: String): String = url.substringBefore('#').substringBefore('?')
}
