package com.devilcut.pinsaver.core

import kotlinx.serialization.json.Json

internal data class FoundVideos(
    val clips: List<RawClip>,
    val title: String?,
    val thumbnail: String?,
)

/** Extracts videos from the HTML of a `pinterest.com/pin/<id>/` page. */
internal object HtmlPinParser {
    private val SCRIPT = Regex(
        """<script\b([^>]*)>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val json = Json { isLenient = true }

    fun parse(html: String, pinId: String): FoundVideos {
        val roots = jsonScripts(html).mapNotNull { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val scan = VideoJsonScanner.scan(roots, pinId)
        // Pin pages also embed "more like this" Pins. If we could not tell which
        // object is the requested Pin, the first video on the page is the best guess.
        var clips = if (scan.matchedPin) scan.clips else scan.clips.take(1)
        if (clips.isEmpty()) clips = looseClips(html).take(1)
        return FoundVideos(
            clips = clips,
            title = scan.title ?: metaContent(html, "og:title")?.let(::cleanTitle),
            thumbnail = scan.thumbnail ?: metaContent(html, "og:image"),
        )
    }

    /** Reads the Pin id from `<link rel="canonical">` or `og:url`. */
    fun canonicalPinId(html: String): String? {
        val canonical = LINK_TAG.findAll(html)
            .map { it.value }
            .firstOrNull { attribute(it, "rel").equals("canonical", ignoreCase = true) }
            ?.let { attribute(it, "href") }
        return listOfNotNull(canonical, metaContent(html, "og:url"))
            .firstNotNullOfOrNull { PinterestLinks.pinIdFromUrl(it) }
    }

    private fun jsonScripts(html: String): List<String> =
        SCRIPT.findAll(html)
            .filter { it.groupValues[1].contains("json", ignoreCase = true) }
            .map { it.groupValues[2].trim() }
            .filter { it.startsWith("{") || it.startsWith("[") }
            .toList()

    private fun looseClips(html: String): List<RawClip> {
        val text = html
            .replace("\\/", "/")
            .replace(Regex("""\\u002[fF]"""), "/")
            .replace("&amp;", "&")
        val urls = VideoUrls.LOOSE_PINIMG.findAll(text).map { it.value }.distinct().toList()
        val groups = urls.map { listOf(RawVariant(it, null, null, null, null, null)) }
        return VideoJsonScanner.mergeByStem(groups)
    }

    private fun metaContent(html: String, property: String): String? =
        META_TAG.findAll(html)
            .map { it.value }
            .firstOrNull { tag ->
                attribute(tag, "property").equals(property, ignoreCase = true) ||
                    attribute(tag, "name").equals(property, ignoreCase = true)
            }
            ?.let { attribute(it, "content") }
            ?.let(::decodeEntities)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun attribute(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
            .find(tag)
            ?.let { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }

    private fun cleanTitle(title: String): String? =
        title.removeSuffix(" | Pinterest").trim().takeIf { it.isNotEmpty() && !it.equals("Pinterest", true) }

    private fun decodeEntities(text: String): String =
        Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|#39);""").replace(text) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos", "#39" -> "'"
                else -> {
                    val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                    code?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
                }
            }
        }
}
