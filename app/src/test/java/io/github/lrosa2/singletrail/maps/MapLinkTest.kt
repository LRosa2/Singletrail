package io.github.lrosa2.singletrail.maps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MapLinkTest {
    private val swiss = "mf-V4-map://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/europe/Switzerland.zip"

    private fun rejected(link: String): MapLinkException.Reason = assertThrows<MapLinkException> { MapLink.parse(link) }.reason

    @Test
    fun `an OpenAndroMaps install link becomes an https download`() {
        val link = MapLink.parse(swiss)
        assertEquals("https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/europe/Switzerland.zip", link.downloadUrl)
        assertEquals("Switzerland", link.region)
        assertEquals("Switzerland.zip", link.fileName)
        assertEquals("https://www.openandromaps.org/en/downloads/europe", link.pageUrl)
    }

    @Test
    fun `both spellings of the scheme work`() {
        assertEquals(MapLink.parse(swiss), MapLink.parse(swiss.replace("mf-V4-map", "mf-v4-map")))
    }

    @Test
    fun `underscores in the region name read as spaces`() {
        val link = MapLink.parse("mf-V4-map://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/europe/Czech_Republic.zip")
        assertEquals("Czech Republic", link.region)
    }

    @Test
    fun `a host that isn't an OpenAndroMaps mirror is refused`() {
        assertEquals(MapLinkException.Reason.HOST_NOT_ALLOWED, rejected(swiss.replace("ftp.gwdg.de", "evil.example.com")))
        assertEquals(MapLinkException.Reason.HOST_NOT_ALLOWED, rejected(swiss.replace("ftp.gwdg.de", "ftp.gwdg.de.evil.example.com")))
        assertEquals(MapLinkException.Reason.HOST_NOT_ALLOWED, rejected(swiss.replace("ftp.gwdg.de", "user@ftp.gwdg.de")))
        assertEquals(MapLinkException.Reason.HOST_NOT_ALLOWED, rejected(swiss.replace("ftp.gwdg.de", "ftp.gwdg.de:8080")))
    }

    @Test
    fun `points of interest and other files are not maps`() {
        val poi = "mf-V4-map://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/pois/mapsforge/europe/Switzerland.Poi.zip"
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected(poi))
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected(swiss.replace("mapsV5/europe/Switzerland.zip", "mapsV5/europe/Switzerland.Poi.zip")))
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected(swiss.replace(".zip", ".exe")))
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected("mf-V4-map://ftp.gwdg.de/pub/other/Switzerland.zip"))
    }

    @Test
    fun `tricks in the path are refused`() {
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected(swiss.replace("europe/", "europe/../../../../")))
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected(swiss.replace("europe/", "europe//")))
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected("$swiss?x=1"))
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected("$swiss#x"))
        assertEquals(MapLinkException.Reason.NOT_A_MAP, rejected(swiss.replace("Switzerland", "Swi%2Fzerland")))
    }

    @Test
    fun `anything else is not a map link`() {
        assertEquals(MapLinkException.Reason.NOT_A_MAP_LINK, rejected("https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/europe/Switzerland.zip"))
        assertEquals(MapLinkException.Reason.NOT_A_MAP_LINK, rejected("orux-map://ftp.gwdg.de/pub/x.zip"))
        assertEquals(MapLinkException.Reason.NOT_A_MAP_LINK, rejected("not a link"))
        assertEquals(MapLinkException.Reason.NOT_A_MAP_LINK, rejected("mf-V4-map://" + "a".repeat(3000)))
    }

    @Test
    fun `an installed map's download finds its region page again`() {
        assertEquals("https://www.openandromaps.org/en/downloads/europe", MapLink.pageFor(MapLink.parse(swiss).downloadUrl))
        assertEquals("https://www.openandromaps.org/en/downloads", MapLink.pageFor(null))
        assertEquals("https://www.openandromaps.org/en/downloads", MapLink.pageFor("https://example.com/x.zip"))
    }

    @Test
    fun `a region outside Europe opens the general downloads page`() {
        val link = MapLink.parse("mf-V4-map://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/asia/Nepal.zip")
        assertEquals("https://www.openandromaps.org/en/downloads", link.pageUrl)
    }
}
