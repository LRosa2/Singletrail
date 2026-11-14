package io.github.lrosa2.singletrail.data.collections

/** Collection names (spec §8.1, §8.4 step 7, D48). */
object CollectionNames {
    const val MAX_LENGTH = 80
    private val spaces = Regex("""\s+""")
    private val zipSuffix = Regex("""\.zip$""", RegexOption.IGNORE_CASE)

    /** [raw] trimmed, inner whitespace collapsed and cut to [MAX_LENGTH]; null when nothing is left. */
    fun clean(raw: String?): String? = raw?.replace(spaces, " ")?.trim()?.take(MAX_LENGTH)?.trim()?.ifEmpty { null }

    /** The collection a zip called [fileName] fills: its name without ".zip". */
    fun fromZip(fileName: String): String? = clean(fileName.substringAfterLast('/').replace(zipSuffix, ""))
}
