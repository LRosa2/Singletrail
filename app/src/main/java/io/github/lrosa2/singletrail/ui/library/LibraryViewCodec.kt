package io.github.lrosa2.singletrail.ui.library

import io.github.lrosa2.singletrail.data.settings.LibraryViewSettings

/** The library's choices to and from settings (spec §8.1). */
object LibraryViewCodec {
    private const val ALL = "all"
    private const val NONE = "none"
    private const val ONE = "id:"

    fun collection(stored: String?): CollectionChoice = when {
        stored == NONE -> CollectionChoice.NotInAny
        stored != null && stored.startsWith(ONE) && stored.length > ONE.length -> CollectionChoice.One(stored.removePrefix(ONE))
        else -> CollectionChoice.All
    }

    fun collectionKey(choice: CollectionChoice): String = when (choice) {
        CollectionChoice.All -> ALL
        CollectionChoice.NotInAny -> NONE
        is CollectionChoice.One -> ONE + choice.id
    }

    fun filters(saved: LibraryViewSettings) = LibraryFilters(
        minStars = saved.minStars?.takeIf { it in 1..5 },
        length = LengthBand.entries.firstOrNull { it.name == saved.length },
        climb = ClimbBand.entries.firstOrNull { it.name == saved.climb },
        notRidden = saved.notRidden,
        loopsOnly = saved.loopsOnly,
    )

    fun settings(sort: LibrarySort, filters: LibraryFilters, collection: CollectionChoice) = LibraryViewSettings(
        sort = sort.name,
        minStars = filters.minStars,
        length = filters.length?.name,
        climb = filters.climb?.name,
        notRidden = filters.notRidden,
        loopsOnly = filters.loopsOnly,
        collection = collectionKey(collection),
    )
}
