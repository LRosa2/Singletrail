package io.github.lrosa2.singletrail.util

/** File names for shared and exported files, safe on phones, PCs and in zips. */
object FileNames {
    private const val MAX_LENGTH = 80
    private val reserved = Regex("""[\\/:*?"<>|]""")

    fun safe(name: String, extension: String, fallback: String = "route"): String {
        val cleaned = name
            .filterNot { it.isISOControl() }
            .replace(reserved, "_")
            .trim(' ', '.')
            .take(MAX_LENGTH)
            .trim(' ', '.')
        val base = if (cleaned.isEmpty() || cleaned.all { it == '_' || it == ' ' }) fallback else cleaned
        return "$base.$extension"
    }

    /** Hands out names that differ even on case-insensitive file systems: "A.gpx", "A (2).gpx", … */
    class Unique {
        private val used = HashSet<String>()

        fun next(name: String, extension: String): String {
            val first = safe(name, extension)
            if (used.add(first.lowercase())) return first
            val base = first.removeSuffix(".$extension")
            var n = used.size + 1
            while (true) {
                val candidate = "$base ($n).$extension"
                if (used.add(candidate.lowercase())) return candidate
                n++
            }
        }
    }
}
