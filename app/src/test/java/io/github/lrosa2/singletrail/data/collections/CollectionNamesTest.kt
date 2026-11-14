package io.github.lrosa2.singletrail.data.collections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CollectionNamesTest {

    @Test
    fun `zip names lose their extension, in any case`() {
        assertEquals("SchweizMobil-MTB-Ostschweiz", CollectionNames.fromZip("SchweizMobil-MTB-Ostschweiz.zip"))
        assertEquals("Routes", CollectionNames.fromZip("Routes.ZIP"))
        assertEquals("routes (1)", CollectionNames.fromZip("routes (1).zip"))
        assertEquals("routes.gpx", CollectionNames.fromZip("routes.gpx"))
    }

    @Test
    fun `a zip named by a path or a link keeps only its last part`() {
        assertEquals("Ostschweiz", CollectionNames.fromZip("downloads/Ostschweiz.zip"))
    }

    @Test
    fun `spaces are tidied`() {
        assertEquals("Waldegg loops", CollectionNames.clean("  Waldegg   loops "))
        assertEquals("To ride", CollectionNames.clean("To\tride"))
    }

    @Test
    fun `blank names are no names`() {
        assertNull(CollectionNames.clean("   "))
        assertNull(CollectionNames.clean(null))
        assertNull(CollectionNames.fromZip(".zip"))
    }

    @Test
    fun `long names are cut`() {
        assertEquals(CollectionNames.MAX_LENGTH, CollectionNames.clean("a".repeat(200))!!.length)
    }
}
