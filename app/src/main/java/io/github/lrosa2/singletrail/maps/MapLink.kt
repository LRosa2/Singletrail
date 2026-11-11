package io.github.lrosa2.singletrail.maps

/**
 * An OpenAndroMaps install link (spec §7.2, D40), e.g.
 * `mf-V4-map://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/europe/Switzerland.zip`.
 * The same host and path serve the zip over https.
 */
data class MapLink(
    /** For people: "Czech Republic". */
    val region: String,
    val fileName: String,
    val downloadUrl: String,
    /** The region's page on the website, for "Check for update". */
    val pageUrl: String,
) {
    companion object {
        /** Scheme matching in intent filters is case-sensitive, so both spellings are registered. */
        val SCHEMES = listOf("mf-v4-map", "mf-V4-map")

        /** Known OpenAndroMaps mirrors; any other host could make the app fetch arbitrary files. */
        val HOSTS = setOf("ftp.gwdg.de")

        const val DOWNLOADS_PAGE = "https://www.openandromaps.org/en/downloads"
        private const val MAX_LENGTH = 2048

        // Only map zips under the OpenAndroMaps maps folders: no dots, escapes, queries or empty segments.
        private val PATH = Regex("^/pub/misc/openstreetmap/openandromaps/maps[A-Za-z0-9]*/((?:[A-Za-z0-9_-]+/)*)([A-Za-z0-9_-]+)\\.zip$")
        private val PAGES = setOf("europe")

        /** The region page for a map installed from [downloadUrl] ("Check for update", spec §7.3). */
        fun pageFor(downloadUrl: String?): String {
            val path = downloadUrl?.removePrefix("https://")?.let { "/" + it.substringAfter('/') } ?: return DOWNLOADS_PAGE
            val match = PATH.matchEntire(path) ?: return DOWNLOADS_PAGE
            val folder = match.groupValues[1].substringBefore('/').lowercase()
            return if (folder in PAGES) "$DOWNLOADS_PAGE/$folder" else DOWNLOADS_PAGE
        }

        /** @throws MapLinkException when the link isn't an OpenAndroMaps map link on an allowed host */
        fun parse(link: String): MapLink {
            val text = link.trim()
            if (text.length > MAX_LENGTH || text.any { it.isWhitespace() } || !text.contains("://")) {
                throw MapLinkException(MapLinkException.Reason.NOT_A_MAP_LINK)
            }
            val scheme = text.substringBefore("://")
            if (!scheme.equals(SCHEMES.first(), ignoreCase = true)) throw MapLinkException(MapLinkException.Reason.NOT_A_MAP_LINK)
            val rest = text.substringAfter("://")
            val host = rest.substringBefore('/')
            if (host.lowercase() !in HOSTS) throw MapLinkException(MapLinkException.Reason.HOST_NOT_ALLOWED)
            if (!rest.contains('/')) throw MapLinkException(MapLinkException.Reason.NOT_A_MAP)
            val path = "/" + rest.substringAfter('/')
            val match = PATH.matchEntire(path) ?: throw MapLinkException(MapLinkException.Reason.NOT_A_MAP)
            val name = match.groupValues[2]
            val downloadUrl = "https://${host.lowercase()}$path"
            return MapLink(
                region = name.replace('_', ' '),
                fileName = "$name.zip",
                downloadUrl = downloadUrl,
                pageUrl = pageFor(downloadUrl),
            )
        }
    }
}

class MapLinkException(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason { NOT_A_MAP_LINK, HOST_NOT_ALLOWED, NOT_A_MAP }
}
