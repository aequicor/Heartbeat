package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import java.nio.file.Path

/** Pi's include, exclude and exact override order, evaluated against the same three path forms as Pi. */
internal class PiResourcePatterns(
    private val entries: List<String>,
    private val base: Path,
    private val globs: ResourceGlobs,
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
        globs.matches(pattern.removePrefix("./"), it)
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
 * Glob matching of one inventory read. Masks and ignore files may come from a project, so all matching shares one
 * step allowance and an expanded pattern is reused. A mask beyond the limits of a single match only counts as not
 * matching; once the allowance is spent no mask matches any more. Either marks the listing incomplete.
 */
internal class ResourceGlobs(private var steps: Int = MAX_TOTAL_STEPS) {
    var isIncomplete: Boolean = false
        private set
    val isSpent: Boolean get() = steps <= 0
    private val expansions = HashMap<String, List<String>?>()

    fun matches(pattern: String, value: String): Boolean {
        val alternatives = if (isSpent || value.length > MAX_PATH) null else alternatives(pattern)
        if (alternatives == null) isIncomplete = true
        return alternatives.orEmpty().any { ResourceGlob(it, value, this).matches() }
    }

    /** Charges [cost] steps of the shared allowance; false once it is spent. */
    fun spend(cost: Int = 1): Boolean {
        steps -= cost
        if (steps < 0) isIncomplete = true
        return steps >= 0
    }

    /** A single match beyond its own limit: that mask does not match, later ones still do. */
    fun refuse(): Boolean {
        isIncomplete = true
        return false
    }

    private fun alternatives(pattern: String): List<String>? = if (pattern in expansions) {
        expansions[pattern]
    } else {
        (if (pattern.length > MAX_PATTERN) null else expandBraces(pattern)).also { expansions[pattern] = it }
    }

    /** The brace alternatives of [pattern], each charged by its length; null when there are too many. */
    private fun expandBraces(pattern: String): List<String>? {
        val pending = ArrayDeque<String>()
        val result = mutableListOf<String>()
        pending.add(pattern)
        var isRefused = false
        while (!isRefused && pending.isNotEmpty()) {
            val next = pending.removeFirst()
            isRefused = pending.size + result.size >= MAX_ALTERNATIVES || !spend(next.length)
            val end = next.indexOf('}')
            val start = if (end >= 0) next.lastIndexOf('{', end) else -1
            val parts = if (start >= 0) next.substring(start + 1, end).split(',') else emptyList()
            when {
                isRefused -> Unit
                parts.size > 1 -> parts.forEach { pending.add(next.take(start) + it + next.substring(end + 1)) }
                else -> result.add(next)
            }
        }
        return result.takeUnless { isRefused }
    }
}

/** Portable glob matching with a bounded state graph instead of regex backtracking. */
internal fun piResourceGlob(pattern: String, value: String): Boolean = ResourceGlobs().matches(pattern, value)

private class ResourceGlob(private val pattern: String, private val value: String, private val globs: ResourceGlobs) {
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

    /** One more state within this match's own limit and the read's shared allowance. */
    private fun spend(): Boolean = if (visited.size > MAX_STATES) globs.refuse() else globs.spend()

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
private const val MAX_TOTAL_STEPS = 4_000_000
private const val RANGE_LENGTH = 3
