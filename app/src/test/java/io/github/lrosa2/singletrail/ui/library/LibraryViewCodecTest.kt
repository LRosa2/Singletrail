package io.github.lrosa2.singletrail.ui.library

import io.github.lrosa2.singletrail.data.settings.LibraryViewSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryViewCodecTest {

    @Test
    fun `choices survive being stored and read back`() {
        val filters = LibraryFilters(minStars = 3, length = LengthBand.KM_20_TO_40, climb = ClimbBand.OVER_1000_M, notRidden = true, loopsOnly = true)
        for (choice in listOf(CollectionChoice.All, CollectionChoice.NotInAny, CollectionChoice.One("c-42"))) {
            val stored = LibraryViewCodec.settings(LibrarySort.LENGTH, filters, choice)
            assertEquals("LENGTH", stored.sort)
            assertEquals(filters, LibraryViewCodec.filters(stored))
            assertEquals(choice, LibraryViewCodec.collection(stored.collection))
        }
    }

    @Test
    fun `nothing stored means no filters and all routes`() {
        assertEquals(LibraryFilters(), LibraryViewCodec.filters(LibraryViewSettings()))
        assertEquals(CollectionChoice.All, LibraryViewCodec.collection(null))
    }

    @Test
    fun `values it doesn't know reset to the default`() {
        val odd = LibraryViewSettings(minStars = 9, length = "HUGE", climb = "", collection = "id:")
        assertEquals(LibraryFilters(), LibraryViewCodec.filters(odd))
        assertEquals(CollectionChoice.All, LibraryViewCodec.collection("id:"))
        assertEquals(CollectionChoice.All, LibraryViewCodec.collection("something else"))
    }
}
