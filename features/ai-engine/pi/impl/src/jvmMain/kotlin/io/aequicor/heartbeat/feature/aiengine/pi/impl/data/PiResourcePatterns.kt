package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.coroutines.Job
import java.nio.file.Path
import kotlin.time.TimeMark

/** Pi's include, exclude and exact override order, evaluated against the same three path forms as Pi. */
internal class PiResourcePatterns(entries: List<String>, private val base: Path, private val globs: ResourceGlobs) {
    private val patterns = entries.filter { it.isResourcePattern() }
    private val includes = patterns.filter { it.firstOrNull() !in listOf('!', '+', '-') }
    private val exclusions = patterns.filter { it.startsWith('!') }.map { it.drop(1) }
    private val exactIncludes = patterns.filter { it.startsWith('+') }.map { it.drop(1).exactForm() }
    private val exactExclusions = patterns.filter { it.startsWith('-') }.map { it.drop(1).exactForm() }

    fun enabled(path: Path, isAutomatic: Boolean): Boolean {
        val named = forms(path, includeName = true)
        val exact = forms(path, includeName = false).toSet()
        var isEnabled = isAutomatic || includes.isEmpty() || includes.any { matches(named, it) }
        if (exclusions.any { matches(named, it) }) isEnabled = false
        if (exactIncludes.any { it in exact }) isEnabled = true
        if (exactExclusions.any { it in exact }) isEnabled = false
        return isEnabled
    }

    private fun matches(forms: List<String>, pattern: String): Boolean = forms.any {
        globs.matches(pattern.removePrefix("./"), it)
    }

    private fun String.exactForm(): String = replace('\\', '/').removePrefix("./")

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
 * The time limit of one inventory read together with its caller's cancellation. Resource files and masks may come from
 * a project, so the walk and the matching stop once the limit passes or the read is cancelled; the listing is then
 * incomplete, and a cancelled caller never sees it.
 */
internal class ResourceLimit(private val deadline: TimeMark, private val job: Job? = null) {
    var isReached: Boolean = false
        private set

    fun isOver(): Boolean {
        if (!isReached && (deadline.hasPassedNow() || job?.isActive == false)) isReached = true
        return isReached
    }
}

/**
 * Glob matching of one inventory read; an expanded pattern is reused. A mask beyond the limits of a single match only
 * counts as not matching, and once the read's [limit] is over no mask matches any more. Either marks the listing
 * incomplete.
 */
internal class ResourceGlobs(private val limit: ResourceLimit) {
    var isIncomplete: Boolean = false
        private set
    private val expansions = HashMap<String, List<String>?>()

    fun matches(pattern: String, value: String): Boolean {
        val alternatives = if (limit.isOver() || value.length > MAX_PATH) null else alternatives(pattern)
        if (alternatives == null) isIncomplete = true
        return alternatives.orEmpty().any { ResourceGlob(it, value, this).matches() }
    }

    /** False once a match visited [states] beyond its own limit or, checked every few states, the read is over. */
    fun proceed(states: Int): Boolean {
        val isStopped = states > MAX_STATES || (states % LIMIT_CHECK_STATES == 0 && limit.isOver())
        if (isStopped) isIncomplete = true
        return !isStopped
    }

    private fun alternatives(pattern: String): List<String>? = if (pattern in expansions) {
        expansions[pattern]
    } else {
        (if (pattern.length > MAX_PATTERN) null else expandBraces(pattern)).also { expansions[pattern] = it }
    }

    /** The brace alternatives of [pattern]; null when there are too many, counted before any is built. */
    private fun expandBraces(pattern: String): List<String>? {
        val pending = ArrayDeque<String>()
        val result = mutableListOf<String>()
        pending.add(pattern)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            val end = next.indexOf('}')
            val start = if (end >= 0) next.lastIndexOf('{', end) else -1
            val parts = if (start >= 0) next.substring(start + 1, end).split(',') else emptyList()
            if (parts.size > 1) {
                if (pending.size + result.size + parts.size > MAX_ALTERNATIVES) return null
                parts.forEach { pending.add(next.take(start) + it + next.substring(end + 1)) }
            } else {
                result.add(next)
            }
        }
        return result
    }
}

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
                isStopped = !globs.proceed(visited.size)
                val (p, v) = position
                isMatched = !isStopped && p == pattern.length && v == value.length
                if (!isStopped && !isMatched && p < pattern.length) step(p, v)
            }
        }
        return isMatched
    }

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
private const val LIMIT_CHECK_STATES = 256
private const val RANGE_LENGTH = 3
