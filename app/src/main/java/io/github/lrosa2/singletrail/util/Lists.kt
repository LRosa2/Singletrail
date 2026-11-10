package io.github.lrosa2.singletrail.util

/** At most [max] evenly spaced items, always keeping the first and the last; the list itself when short enough. */
fun <T> List<T>.thinTo(max: Int): List<T> {
    require(max >= 2) { "max must be at least 2" }
    if (size <= max) return this
    val step = (size - 1 + max - 2) / (max - 1) // ceil((size - 1) / (max - 1))
    return filterIndexed { i, _ -> i % step == 0 || i == lastIndex }
}
