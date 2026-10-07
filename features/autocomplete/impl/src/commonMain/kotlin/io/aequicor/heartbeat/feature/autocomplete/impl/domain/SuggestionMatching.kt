package io.aequicor.heartbeat.feature.autocomplete.impl.domain

/**
 * How well [candidate] (an id, title or path) matches the typed [query]: a case-insensitive prefix beats a
 * substring, everything else does not match at all. An empty query matches everything at the lowest rank, so
 * a bare trigger lists the whole section in its own order.
 */
internal fun queryRank(query: String, candidate: String): Int {
    if (query.isEmpty()) return 2
    val needle = query.lowercase()
    val haystack = candidate.lowercase()
    return when {
        haystack.startsWith(needle) -> 0

        haystack.contains(needle) -> 1

        else -> -1
    }
}
