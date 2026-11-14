package io.github.lrosa2.singletrail.importing

/**
 * Names from imported files without the boilerplate other apps add (spec §8.4, D47). komoot names
 * every exported track "GPX Download: <tour>", which would file all its tours together under G.
 */
internal object RouteNames {
    private val exportPrefix = Regex("""^\s*GPX Download\s*:\s*""", RegexOption.IGNORE_CASE)

    /** [name] without an exporter's prefix; null when nothing is left, so the next name in line is used. */
    fun clean(name: String?): String? = name?.replace(exportPrefix, "")?.trim()?.ifEmpty { null }
}
