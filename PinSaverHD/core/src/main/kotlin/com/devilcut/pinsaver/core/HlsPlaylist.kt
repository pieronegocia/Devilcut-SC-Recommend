package com.devilcut.pinsaver.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HlsVariantStream(
    val uri: String,
    val bandwidth: Long?,
    val averageBandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
) {
    val pixels: Long get() = (width ?: 0).toLong() * (height ?: 0)
    val isHevc: Boolean get() = codecs?.let { it.contains("hvc1") || it.contains("hev1") } == true
}

object HlsPlaylist {
    private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    /**
     * Parses an HLS master playlist.
     *
     * @return the variant streams, or null when [text] is not a master playlist
     * (for example a media playlist that lists segments directly).
     */
    fun parseMaster(text: String, playlistUrl: String): List<HlsVariantStream>? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) return null
        val variants = mutableListOf<HlsVariantStream>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attributes = parseAttributes(line.substringAfter(':'))
                val uriLine = lines.drop(index + 1).firstOrNull { !it.startsWith("#") }
                if (uriLine != null) {
                    val resolution = attributes["RESOLUTION"]?.split('x', 'X')
                    variants += HlsVariantStream(
                        uri = resolve(playlistUrl, uriLine),
                        bandwidth = attributes["BANDWIDTH"]?.toLongOrNull(),
                        averageBandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                        width = resolution?.getOrNull(0)?.toIntOrNull(),
                        height = resolution?.getOrNull(1)?.toIntOrNull(),
                        codecs = attributes["CODECS"],
                    )
                }
            }
            index++
        }
        return variants.takeIf { it.isNotEmpty() }
    }

    private fun parseAttributes(list: String): Map<String, String> =
        ATTRIBUTE.findAll(list).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun resolve(base: String, reference: String): String =
        base.toHttpUrlOrNull()?.resolve(reference)?.toString() ?: reference
}
