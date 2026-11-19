package com.dpsmpx.dpks.data

object MarkdownMeta {
    private val HEADING = Regex("^#{1,6}\\s+(.+)$", RegexOption.MULTILINE)
    private val WIKILINK = Regex("\\[\\[([^\\]|#]+)(?:[|#][^\\]]*)?\\]\\]")
    private val MDLINK = Regex("\\[[^\\]]+\\]\\(([^)]+)\\)")

    fun filenameTitle(relativePath: String): String {
        val base = relativePath.substringAfterLast('/').substringBeforeLast('.')
        return base.ifBlank { relativePath }
    }

    fun titleOf(relativePath: String, body: String): String {
        if (body.isBlank()) return filenameTitle(relativePath)
        HEADING.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val firstLine = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("---") }
        return firstLine?.take(120) ?: filenameTitle(relativePath)
    }

    fun possibleLinks(body: String): List<String> {
        if (body.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        WIKILINK.findAll(body).forEach { out.add(it.groupValues[1].trim()) }
        MDLINK.findAll(body).forEach { raw ->
            val target = raw.groupValues[1].trim().substringBefore('#').substringBefore('?')
            if (target.isNotEmpty() && !target.contains("://")) out.add(target)
        }
        return out.toList()
    }

    /** Minimal FTS query sanitizer: strip quotes and OR/AND/NOT operators noise. */
    fun ftsQuery(input: String): String {
        val tokens = input.trim()
            .replace('"', ' ')
            .split(Regex("\\s+"))
            .map { it.trim { c -> !c.isLetterOrDigit() && c != '_' && c != '-' } }
            .filter { it.length >= 2 }
            .take(12)
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "$it*" }
    }
}
