package com.devilcut.pinsaver.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal data class RawVariant(
    val url: String,
    val formatId: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val thumbnail: String?,
)

internal data class RawClip(val variants: List<RawVariant>) {
    val durationMs: Long? get() = variants.firstNotNullOfOrNull { it.durationMs }
    val thumbnail: String? get() = variants.firstNotNullOfOrNull { it.thumbnail }
}

internal data class ScanResult(
    val clips: List<RawClip>,
    val title: String?,
    val thumbnail: String?,
    /** True when the videos came from an object whose id is the requested Pin. */
    val matchedPin: Boolean,
)

/**
 * Finds videos in any Pinterest JSON payload without depending on exact field names.
 *
 * Pinterest has shipped the same data under many shapes over the years
 * (`videos.video_list.V_720P`, `story_pin_data.pages[].blocks[].video.video_list`,
 * camelCase Relay data such as `videoList720P.v720P`…). They all share one trait:
 * a video rendition is an object with a `url` that points to an `.mp4` or `.m3u8`,
 * usually next to `width`, `height`, `duration` and `thumbnail`. The renditions of
 * one video sit together in the same parent object, and share a file-name hash.
 */
internal object VideoJsonScanner {

    fun scan(roots: List<JsonElement>, pinId: String?): ScanResult {
        val pinObjects = if (pinId == null) emptyList() else roots.flatMap { findObjectsWithId(it, pinId) }
        val pinObjectsWithVideo = pinObjects.filter(::containsVideo)
        val collector = Collector()
        (pinObjectsWithVideo.ifEmpty { roots }).forEach { collector.visit(it, parentKey = null, containerId = null) }
        val metadataSources = pinObjects.ifEmpty { roots.filterIsInstance<JsonObject>() }
        return ScanResult(
            clips = collector.clips(),
            title = metadataSources.firstNotNullOfOrNull(::titleOf),
            thumbnail = metadataSources.firstNotNullOfOrNull(::imageOf),
            matchedPin = pinObjectsWithVideo.isNotEmpty(),
        )
    }

    fun titleOf(obj: JsonObject): String? {
        val raw = TITLE_KEYS.firstNotNullOfOrNull { key -> obj.string(key)?.trim()?.takeIf { it.isNotEmpty() } }
            ?: return null
        val singleLine = raw.replace(Regex("""\s+"""), " ")
        return if (singleLine.length > 100) singleLine.take(100).trimEnd() + "…" else singleLine
    }

    fun imageOf(obj: JsonObject): String? {
        (obj["images"] as? JsonObject)?.let { images ->
            IMAGE_SIZES.firstNotNullOfOrNull { size -> (images[size] as? JsonObject)?.string("url") }?.let { return it }
        }
        IMAGE_OBJECT_KEYS.firstNotNullOfOrNull { key -> (obj[key] as? JsonObject)?.string("url") }?.let { return it }
        return IMAGE_STRING_KEYS.firstNotNullOfOrNull { key -> obj.string(key)?.takeIf { it.startsWith("http") } }
    }

    private fun findObjectsWithId(
        element: JsonElement,
        pinId: String,
        out: MutableList<JsonObject> = mutableListOf(),
    ): List<JsonObject> {
        when (element) {
            is JsonObject -> {
                if (ID_KEYS.any { element.string(it) == pinId }) out += element
                element.values.forEach { findObjectsWithId(it, pinId, out) }
            }
            is JsonArray -> element.forEach { findObjectsWithId(it, pinId, out) }
            else -> Unit
        }
        return out
    }

    private fun containsVideo(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.string("url")?.let { VideoUrls.isVideoUrl(it, strict = false) } == true ||
            element.values.any(::containsVideo)
        is JsonArray -> element.any(::containsVideo)
        is JsonPrimitive -> element.isString && VideoUrls.isVideoUrl(element.content, strict = true)
    }

    private class Collector {
        private var nextContainerId = 0
        private val groups = LinkedHashMap<Any, MutableList<RawVariant>>()

        fun visit(element: JsonElement, parentKey: String?, containerId: Int?) {
            when (element) {
                is JsonObject -> {
                    val url = element.string("url")
                    if (url != null && VideoUrls.isVideoUrl(url, strict = false)) {
                        val variant = RawVariant(
                            url = url,
                            formatId = parentKey,
                            width = element.int("width"),
                            height = element.int("height"),
                            durationMs = element.long("duration"),
                            thumbnail = element.string("thumbnail")?.takeIf { it.startsWith("http") },
                        )
                        groups.getOrPut(containerId ?: nextContainerId++) { mutableListOf() } += variant
                        return
                    }
                    val id = nextContainerId++
                    for ((key, value) in element) visit(value, key, id)
                }
                is JsonArray -> {
                    val id = nextContainerId++
                    element.forEach { visit(it, parentKey, id) }
                }
                is JsonPrimitive -> {
                    if (element.isString && VideoUrls.isVideoUrl(element.content, strict = true)) {
                        val url = element.content
                        groups.getOrPut("stem:" + VideoUrls.stemOf(url)) { mutableListOf() } +=
                            RawVariant(url, parentKey, null, null, null, null)
                    }
                }
            }
        }

        /** Groups renditions into clips, merging groups that share a file-name hash. */
        fun clips(): List<RawClip> = mergeByStem(groups.values.toList())
    }

    fun mergeByStem(groups: List<List<RawVariant>>): List<RawClip> {
        val parent = IntArray(groups.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        val owner = HashMap<String, Int>()
        groups.forEachIndexed { i, variants ->
            variants.forEach { variant ->
                val other = owner.putIfAbsent(VideoUrls.stemOf(variant.url), i)
                if (other != null) {
                    val a = find(i)
                    val b = find(other)
                    if (a != b) parent[maxOf(a, b)] = minOf(a, b)
                }
            }
        }
        val merged = LinkedHashMap<Int, MutableList<RawVariant>>()
        groups.indices.forEach { i -> merged.getOrPut(find(i)) { mutableListOf() }.addAll(groups[i]) }
        return merged.values.map { RawClip(dedupe(it)) }.filter { it.variants.isNotEmpty() }
    }

    private fun dedupe(variants: List<RawVariant>): List<RawVariant> =
        variants.groupBy { it.url }.map { (url, same) ->
            RawVariant(
                url = url,
                formatId = same.firstNotNullOfOrNull { it.formatId },
                width = same.firstNotNullOfOrNull { it.width },
                height = same.firstNotNullOfOrNull { it.height },
                durationMs = same.firstNotNullOfOrNull { it.durationMs },
                thumbnail = same.firstNotNullOfOrNull { it.thumbnail },
            )
        }

    private val ID_KEYS = listOf("id", "entityId", "entity_id")
    private val TITLE_KEYS = listOf(
        "title", "grid_title", "gridTitle", "seo_title", "seoTitle",
        "closeup_unified_title", "closeupUnifiedTitle", "description", "closeup_description",
    )
    private val IMAGE_SIZES = listOf("orig", "736x", "474x", "236x")
    private val IMAGE_OBJECT_KEYS = listOf("imageSpec_orig", "imageSpec_736x", "imageSpec_474x")
    private val IMAGE_STRING_KEYS = listOf("imageLargeUrl", "image_large_url", "imageMediumUrl", "image_medium_url")
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }?.takeIf { it > 0 }

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong()?.takeIf { it > 0 }
