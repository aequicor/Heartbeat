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

/**
 * Ranking of a project file by its relative path: a file whose own name starts with the query beats a path
 * prefix, which beats a substring somewhere inside the path.
 */
internal fun fileRank(query: String, relativePath: String): Int {
    if (query.isEmpty()) return 3
    val needle = query.lowercase()
    val path = relativePath.lowercase()
    val name = path.substringAfterLast('/')
    return when {
        name.startsWith(needle) -> 0
        path.startsWith(needle) -> 1
        path.contains(needle) -> 2
        else -> -1
    }
}
