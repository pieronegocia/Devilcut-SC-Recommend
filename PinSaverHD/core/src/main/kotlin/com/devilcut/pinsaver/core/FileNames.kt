package com.devilcut.pinsaver.core

import java.text.Normalizer

object FileNames {
    private const val MAX_TITLE_LENGTH = 50

    /** e.g. `Receta_de_tacos_123456789_1080p.mp4`. Only ASCII letters, digits, `.`, `_` and `-`. */
    fun build(title: String?, pinId: String, quality: String?, clipIndex: Int = 0, clipCount: Int = 1): String {
        val parts = mutableListOf(title?.let(::sanitize)?.takeIf { it.isNotEmpty() } ?: "pinterest", sanitize(pinId))
        if (clipCount > 1) parts += "clip${clipIndex + 1}"
        quality?.let(::sanitize)?.takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString("_") + ".mp4"
    }

    fun sanitize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .replace(Regex("""_+"""), "_")
            .take(MAX_TITLE_LENGTH)
            .trim('_', '.', '-')
}
