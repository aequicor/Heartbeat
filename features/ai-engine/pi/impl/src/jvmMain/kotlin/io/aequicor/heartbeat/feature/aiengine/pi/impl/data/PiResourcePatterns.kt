package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import java.nio.file.Path

/** Pi's include, exclude and exact override order, evaluated against the same three path forms as Pi. */
internal class PiResourcePatterns(
    private val entries: List<String>,
    private val base: Path,
    private val budget: GlobBudget,
) {
    fun enabled(path: Path, isAutomatic: Boolean): Boolean {
        val patterns = entries.filter { it.isResourcePattern() }
        val includes = patterns.filter { it.firstOrNull() !in listOf('!', '+', '-') }
        var isEnabled = isAutomatic || includes.isEmpty() || includes.any { matches(path, it) }
        if (patterns.any { it.startsWith('!') && matches(path, it.drop(1)) }) isEnabled = false
        if (patterns.any { it.startsWith('+') && exact(path, it.drop(1)) }) isEnabled = true
        if (patterns.any { it.startsWith('-') && exact(path, it.drop(1)) }) isEnabled = false
        return isEnabled
    }

    private fun matches(path: Path, pattern: String): Boolean = forms(path, includeName = true).any {
        piResourceGlob(pattern.removePrefix("./"), it, budget)
    }

    private fun exact(path: Path, pattern: String): Boolean =
        pattern.replace('\\', '/').removePrefix("./") in forms(path, includeName = false)

    private fun forms(path: Path, includeName: Boolean): List<String> {
        val paths = if (path.fileName.toString() == "SKILL.md") listOf(path, path.parent) else listOf(path)
        return paths.flatMap {
            listOfNotNull(base.relativize(it).posix(), it.posix(), it.fileName.toString().takeIf { includeName })
        }
    }
}

internal fun String.isResourcePattern(): Boolean = firstOrNull() in listOf('!', '+', '-') || '*' in this || '?' in this

internal fun Path.posix(): String = toString().replace('\\', '/')

/**
 * Work allowance of one inventory read. Masks and ignore files may come from a project, so their total matching work
 * is bounded: a mask beyond the limits counts as not matching and marks the listing incomplete instead of failing it.
 */
internal class GlobBudget(private var states: Int = MAX_TOTAL_STATES) {
    var isExceeded: Boolean = false
        private set

    fun spend(): Boolean {
        if (states > 0) states-- else isExceeded = true
        return !isExceeded
    }

    fun exceed(): Boolean {
        isExceeded = true
        return false
    }
}

/** Portable glob matching with a bounded state graph instead of regex backtracking. */
internal fun piResourceGlob(pattern: String, value: String, budget: GlobBudget = GlobBudget()): Boolean {
    if (budget.isExceeded) return false
    if (pattern.length > MAX_PATTERN || value.length > MAX_PATH) return budget.exceed()
    val alternatives = expandBraces(pattern) ?: return budget.exceed()
    return alternatives.any { ResourceGlob(it, value, budget).matches() }
}

/** The brace alternatives of [pattern], or null when there are too many to evaluate. */
private fun expandBraces(pattern: String): List<String>? {
    val pending = ArrayDeque<String>()
    val result = mutableListOf<String>()
    pending.add(pattern)
    while (pending.isNotEmpty()) {
        if (pending.size + result.size > MAX_ALTERNATIVES) return null
        val next = pending.removeFirst()
        val end = next.indexOf('}')
        val start = if (end >= 0) next.lastIndexOf('{', end) else -1
        val parts = if (start >= 0) next.substring(start + 1, end).split(',') else emptyList()
        if (parts.size > 1) {
            parts.forEach { pending.add(next.take(start) + it + next.substring(end + 1)) }
        } else {
            result.add(next)
        }
    }
    return result
}

private class ResourceGlob(private val pattern: String, private val value: String, private val budget: GlobBudget) {
    private val pending = ArrayDeque<Pair<Int, Int>>()
    private val visited = mutableSetOf<Pair<Int, Int>>()

    fun matches(): Boolean {
        pending.add(0 to 0)
        var isMatched = false
        var isStopped = false
        while (!isMatched && !isStopped && pending.isNotEmpty()) {
            val position = pending.removeFirst()
            if (visited.add(position)) {
                isStopped = !spend()
                val (p, v) = position
                isMatched = !isStopped && p == pattern.length && v == value.length
                if (!isStopped && !isMatched && p < pattern.length) step(p, v)
            }
        }
        return isMatched
    }

    /** One more state within this match's own limit and the inventory's shared one. */
    private fun spend(): Boolean = if (visited.size > MAX_STATES) budget.exceed() else budget.spend()

    private fun step(p: Int, v: Int) {
        when (pattern[p]) {
            '*' -> star(p, v)
            '?' -> if (ordinary(v)) pending.add(p + 1 to v + 1)
            '[' -> characterClass(p, v)
            else -> if (value.getOrNull(v) == pattern[p]) pending.add(p + 1 to v + 1)
        }
    }

    private fun star(p: Int, v: Int) {
        val isDouble = pattern.getOrNull(p + 1) == '*'
        val end = p + if (isDouble) 2 else 1
        val isSegmentStart = p == 0 || pattern[p - 1] == '/'
        val isSegmentEnd = end == pattern.length || pattern.getOrNull(end) == '/'
        val isCrossingSlash = isDouble && isSegmentStart && isSegmentEnd
        pending.add(end to v)
        if (isCrossingSlash && pattern.getOrNull(end) == '/' && atSegmentStart(v)) pending.add(end + 1 to v)
        val isConsumingSlash = isCrossingSlash && value.getOrNull(v) == '/'
        if (ordinary(v) || isConsumingSlash) pending.add(p to v + 1)
    }

    private fun characterClass(p: Int, v: Int) {
        val end = pattern.indexOf(']', p + 1)
        val group = if (end > p) characterGroup(pattern.substring(p + 1, end)) else null
        if (group == null) {
            if (value.getOrNull(v) == '[') pending.add(p + 1 to v + 1)
        } else if (ordinary(v) && group(value[v])) {
            pending.add(end + 1 to v + 1)
        }
    }

    private fun atSegmentStart(v: Int): Boolean = v == 0 || value[v - 1] == '/'

    private fun ordinary(v: Int): Boolean {
        val char = value.getOrNull(v) ?: return false
        val isDot = char == '.' && atSegmentStart(v)
        return char != '/' && !isDot
    }
}

/** Invalid character classes are literal in minimatch, rather than fatal regular expressions. */
private fun characterGroup(text: String): ((Char) -> Boolean)? {
    val isNegated = text.startsWith('!') || text.startsWith('^')
    val body = if (isNegated) text.drop(1) else text
    if (body.isEmpty()) return null
    val ranges = mutableListOf<CharRange>()
    var index = 0
    while (index < body.length) {
        if (body.getOrNull(index + 1) == '-' && index + 2 < body.length) {
            if (body[index] > body[index + 2]) return null
            ranges.add(body[index]..body[index + 2])
            index += RANGE_LENGTH
        } else {
            ranges.add(body[index]..body[index])
            index++
        }
    }
    return { char -> ranges.any { char in it } != isNegated }
}

private const val MAX_ALTERNATIVES = 256
private const val MAX_PATTERN = 4096
private const val MAX_PATH = 32768
private const val MAX_STATES = 262144
private const val MAX_TOTAL_STATES = 4_000_000
private const val RANGE_LENGTH = 3
