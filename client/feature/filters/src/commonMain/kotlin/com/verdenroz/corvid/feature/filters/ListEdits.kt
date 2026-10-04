package com.verdenroz.corvid.feature.filters

internal fun <T> List<T>.replaced(
    index: Int,
    item: T,
): List<T> {
    val copy = toMutableList()
    copy[index] = item
    return copy
}

internal fun <T> List<T>.removedAt(index: Int): List<T> {
    val copy = toMutableList()
    copy.removeAt(index)
    return copy
}

/** Unchanged when [to] is off either end. */
internal fun <T> List<T>.moved(
    from: Int,
    to: Int,
): List<T> {
    if (to !in indices) return this
    val copy = toMutableList()
    copy.add(to, copy.removeAt(from))
    return copy
}
