package io.aequicor.heartbeat.core.common.release

/**
 * Whether [candidate] is a newer version than [installed]: dot-separated numeric segments compare numerically, and a
 * pre-release (`1.2.0-beta`) is older than its release. Unparsable versions are never newer.
 */
public fun isNewerVersion(candidate: String, installed: String): Boolean {
    val next = parseVersion(candidate) ?: return false
    val current = parseVersion(installed) ?: return candidate != installed
    val size = maxOf(next.numbers.size, current.numbers.size)
    for (index in 0 until size) {
        val left = next.numbers.getOrElse(index) { 0 }
        val right = current.numbers.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return !next.isPreRelease && current.isPreRelease
}

private data class ParsedVersion(val numbers: List<Long>, val isPreRelease: Boolean)

private val VersionPattern = Regex("""v?(\d+(?:\.\d+)*)(-[^+]+)?(\+.+)?""")

private fun parseVersion(value: String): ParsedVersion? {
    val match = VersionPattern.matchEntire(value.trim()) ?: return null
    val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
    return ParsedVersion(numbers, isPreRelease = match.groupValues[2].isNotEmpty())
}
