package com.devilcut.pinsaver.core

/** How a variant is delivered: a direct MP4 file, or an HLS stream that has to be remuxed. */
enum class VariantKind { MP4, HLS }

/** One downloadable quality of a video. */
data class VideoVariant(
    val url: String,
    val kind: VariantKind,
    val width: Int? = null,
    val height: Int? = null,
    /** Short side guessed from the URL (e.g. `/720p/`) when width/height are unknown. */
    val shortSideHint: Int? = null,
    /** Pinterest's own key for this format (`V_720P`, `V_HLSV4`, `V_EXP7`…), if known. */
    val formatId: String? = null,
    val isHevc: Boolean = false,
    /** Exact size reported by the CDN. */
    val sizeBytes: Long? = null,
    /** Size estimated from the HLS bitrate and the duration. */
    val estimatedBytes: Long? = null,
    /** Bits per second, for HLS variants. */
    val bitrate: Long? = null,
    /** True when the URL was derived from the HLS playlist instead of listed by Pinterest. */
    val derived: Boolean = false,
) {
    /** The "p" number: 1080 for both 1920x1080 and 1080x1920 videos. */
    val shortSide: Int?
        get() = if (width != null && height != null && width > 0 && height > 0) minOf(width, height) else shortSideHint

    /** `"1080p"`, or null when the resolution is unknown. */
    val qualityLabel: String? get() = shortSide?.let { "${it}p" }

    val isHd: Boolean get() = (shortSide ?: 0) >= 720

    internal val rankScore: Long
        get() = when {
            width != null && height != null && width > 0 && height > 0 -> width.toLong() * height
            shortSideHint != null -> shortSideHint.toLong() * shortSideHint * 16 / 9
            else -> 0L
        }
}

/** One video inside a Pin. Most Pins have exactly one; old multi-page "Idea Pins" can have several. */
data class VideoClip(
    val index: Int,
    /** Sorted best first. Never empty. */
    val variants: List<VideoVariant>,
    val durationMs: Long?,
    val thumbnailUrl: String?,
) {
    val best: VideoVariant get() = variants.first()
}

data class PinInfo(
    val pinId: String,
    val title: String?,
    val thumbnailUrl: String?,
    val clips: List<VideoClip>,
)

class PinterestException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason { INVALID_LINK, NETWORK, NOT_FOUND, NO_VIDEO }
}

internal object VariantRanking {
    private val comparator: Comparator<VideoVariant> =
        compareByDescending<VideoVariant> { it.rankScore }
            .thenBy { if (it.kind == VariantKind.MP4) 0 else 1 }
            .thenBy { if (it.isHevc) 1 else 0 }
            .thenBy { if (it.derived) 1 else 0 }
            .thenByDescending { it.bitrate ?: it.sizeBytes ?: 0L }

    /**
     * Sorts best first and drops options that would only confuse the user:
     * an HLS stream is kept only when it beats every direct MP4, and only one
     * entry is kept per resolution/format/codec combination.
     */
    fun rankAndPrune(variants: List<VideoVariant>): List<VideoVariant> {
        val sorted = variants.distinctBy { it.url }.sortedWith(comparator)
        val bestMp4 = sorted.filter { it.kind == VariantKind.MP4 }.maxOfOrNull { it.rankScore }
        return sorted
            .filter { it.kind == VariantKind.MP4 || bestMp4 == null || it.rankScore > bestMp4 }
            .distinctBy { Triple(it.shortSide ?: it.url, it.kind, it.isHevc) }
    }
}
