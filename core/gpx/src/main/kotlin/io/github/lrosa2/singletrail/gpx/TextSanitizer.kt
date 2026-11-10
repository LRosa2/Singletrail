package io.github.lrosa2.singletrail.gpx

/**
 * Turns untrusted GPX text (which often contains HTML) into plain text for display (spec §9, S2).
 * The result is never rendered as HTML; this only makes it readable and bounded.
 */
object TextSanitizer {
    const val DEFAULT_MAX_LENGTH = 10_000
    private const val ELLIPSIS = "…"

    private val scriptOrStyle = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val lineBreakTags = Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6])\\s*>")
    private val tag = Regex("</?[a-zA-Z][^<>]*>")
    private val entity = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos|nbsp);")
    private val controlChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val horizontalSpace = Regex("[\\t\\u00A0\\u2007\\u202F ]+")
    private val manyBlankLines = Regex("\n{3,}")

    fun plainText(raw: String?, maxLength: Int = DEFAULT_MAX_LENGTH): String? {
        if (raw == null) return null
        var text = scriptOrStyle.replace(raw, "")
        text = lineBreakTags.replace(text, "\n")
        text = tag.replace(text, "")
        text = decodeEntities(text)
        text = normalizeWhitespace(text)
        if (text.isEmpty()) return null
        return if (text.length <= maxLength) text else text.take(maxLength - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }

    // Single pass, so "&amp;lt;" becomes "&lt;" and is not decoded a second time.
    private fun decodeEntities(text: String): String = entity.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16))
            body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
            body == "amp" -> "&"
            body == "lt" -> "<"
            body == "gt" -> ">"
            body == "quot" -> "\""
            body == "apos" -> "'"
            else -> " " // nbsp
        }
    }

    private fun codePoint(value: Int?): String =
        if (value != null && Character.isValidCodePoint(value)) String(Character.toChars(value)) else ""

    private fun normalizeWhitespace(text: String): String {
        val lines = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .let { controlChars.replace(it, "") }
            .split('\n')
            .map { horizontalSpace.replace(it, " ").trim() }
        return manyBlankLines.replace(lines.joinToString("\n"), "\n\n").trim()
    }
}
