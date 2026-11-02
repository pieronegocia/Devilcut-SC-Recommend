package com.devilcut.pinsaver.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface PinLink {
    /** A link that already contains the Pin id. */
    data class Pin(val id: String) : PinLink

    /** A `pin.it` short link that has to be followed to find the Pin id. */
    data class Short(val url: String) : PinLink
}

object PinterestLinks {
    private val URL_IN_TEXT = Regex("""https?://[^\s<>"'“”]+""", RegexOption.IGNORE_CASE)
    private val URL_WITHOUT_SCHEME = Regex(
        """(?<![\w.-])(?:[\w-]+\.)*(?:pinterest\.[a-z]{2,3}(?:\.[a-z]{2})?|pin\.it)/[^\s<>"'“”]+""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_PIN_ID = Regex("""^\d{5,25}$""")
    private val PIN_SEGMENT = Regex("""/pin/([^/?#\s]+)""", RegexOption.IGNORE_CASE)
    private val NUMERIC_ID = Regex("""^\d{5,25}$""")
    private val ALPHANUMERIC_ID = Regex("""^[A-Za-z0-9_-]{12,64}$""")
    private val PINTEREST_HOST = Regex("""^(?:[\w-]+\.)*pinterest\.[a-z]{2,3}(?:\.[a-z]{2})?$""")

    /**
     * Finds a Pinterest link inside arbitrary text, e.g. the text the Pinterest
     * app puts on the clipboard ("Mira este Pin… https://pin.it/abc123").
     */
    fun parse(input: String): PinLink? {
        val text = input.trim()
        if (BARE_PIN_ID.matches(text)) return PinLink.Pin(text)

        val candidates = URL_IN_TEXT.findAll(text).map { it.value }.toMutableList()
        if (candidates.isEmpty()) {
            URL_WITHOUT_SCHEME.findAll(text).forEach { candidates += "https://${it.value}" }
        }
        for (raw in candidates) {
            val url = raw.trimEnd('.', ',', ')', ']', '}', '!', ';', ':', '…')
            val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: continue
            when {
                host == "pin.it" -> return PinLink.Short(url)
                isPinterestHost(host) -> {
                    pinIdFromUrl(url)?.let { return PinLink.Pin(it) }
                    if (url.contains("/url_shortener/")) return PinLink.Short(url)
                }
            }
        }
        return null
    }

    fun isPinterestHost(host: String): Boolean = PINTEREST_HOST.matches(host.lowercase())

    /**
     * Extracts the id from `/pin/123456789/`, `/pin/some-title--123456789/`
     * or the newer alphanumeric ids.
     */
    fun pinIdFromUrl(url: String): String? {
        val segment = PIN_SEGMENT.find(url)?.groupValues?.get(1) ?: return null
        val id = if (segment.contains("--")) segment.substringAfterLast("--") else segment
        return id.takeIf { NUMERIC_ID.matches(it) || ALPHANUMERIC_ID.matches(it) }
    }
}
