package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * A bounded, confined view of resource files; links are followed only within the root and never recursively twice.
 * An entry that cannot be read or named is skipped and marks the view incomplete, as Pi skips it too.
 */
internal class PiResourceFiles(private val root: Path, private val budget: GlobBudget = GlobBudget()) {
    var isTruncated: Boolean = false
        private set

    /** Entries skipped because they could not be read or are not valid paths. */
    var skipped: Int = 0
        private set
    private val inspected = mutableSetOf<Path>()
    private var readBytes = 0

    fun safe(path: Path): Boolean = reserve(path) && path.normalize().startsWith(root) && try {
        isInside(root, path, PI_MAX_LINKS)
    } catch (e: IOException) {
        log.w(e.redactedFailure()) { "Pi resource link not resolved" }
        skip()
        false
    }

    /** Records an entry Pi would not load either; the caller logs why. */
    fun skip() {
        isTruncated = true
        skipped++
    }

    private fun reserve(path: Path): Boolean {
        if (path in inspected) return true
        if (inspected.size >= MAX_ENTRIES) {
            isTruncated = true
            return false
        }
        inspected.add(path)
        return true
    }

    /** Explicit prompt directories are shallow and do not apply Pi's auto-discovery ignore rules. */
    fun contentPrompts(path: Path): List<Path> = if (safe(path) && Files.isDirectory(path)) {
        children(path).filter { safe(it) && Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
    } else {
        emptyList()
    }

    fun collect(path: Path, kind: ResourceKind, isAutomatic: Boolean = false, isAgents: Boolean = false): List<Path> {
        if (!safe(path)) return emptyList()
        if (Files.isRegularFile(path)) return listOf(path)
        return walk(path, Discovery(path, kind, isAutomatic, isAgents), emptySet(), emptyList())
    }

    fun text(path: Path): String? {
        if (!safe(path) || !Files.isRegularFile(path)) return null
        val bytes = try {
            if (Files.size(path) > MAX_FILE_BYTES || readBytes >= MAX_READ_BYTES) {
                isTruncated = true
                return null
            }
            Files.newInputStream(path).use { it.readNBytes(MAX_FILE_BYTES + 1) }
        } catch (e: IOException) {
            log.w(e.redactedFailure()) { "Pi resource file not readable" }
            skip()
            return null
        }
        readBytes += bytes.size
        if (bytes.size > MAX_FILE_BYTES) {
            isTruncated = true
            return null
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun walk(path: Path, discovery: Discovery, ancestors: Set<Path>, inherited: List<IgnoreRule>): List<Path> {
        if (!safe(path) || !Files.isDirectory(path)) return emptyList()
        if (ancestors.size >= MAX_DEPTH || inspected.size >= MAX_ENTRIES) {
            isTruncated = true
            return emptyList()
        }
        val real = try {
            path.toRealPath()
        } catch (e: IOException) {
            log.w(e.redactedFailure()) { "Pi resource folder not resolved" }
            skip()
            return emptyList()
        }
        if (real in ancestors) return emptyList()
        val rules = inherited + ignores(path)
        val children = children(path).filter { visible(it, rules) }
        val skill = children.firstOrNull { isSkillRoot(it) }
        return when {
            discovery.kind == ResourceKind.Extension -> extensions(path, children)
            discovery.kind == ResourceKind.Skill && skill != null -> listOf(skill)
            else -> descendants(path, discovery, ancestors + setOf(real), rules, children)
        }
    }

    private fun descendants(
        path: Path,
        discovery: Discovery,
        ancestors: Set<Path>,
        rules: List<IgnoreRule>,
        children: List<Path>,
    ): List<Path> {
        val directories = if (discovery.kind == ResourceKind.Prompt && discovery.isAutomatic) {
            emptyList()
        } else {
            children.filter(Files::isDirectory)
        }
        val nested = directories.flatMap {
            walk(it, discovery, ancestors, rules)
        }
        val isMarkdownIncluded =
            discovery.kind == ResourceKind.Prompt || discovery.isAgents != (path == discovery.start)
        val markdown = if (isMarkdownIncluded) children.filter(::isMarkdown) else emptyList()
        return markdown + nested
    }

    private fun visible(path: Path, rules: List<IgnoreRule>): Boolean {
        val name = path.fileName.toString()
        return !name.startsWith('.') && name != "node_modules" && safe(path) && !isIgnored(path, rules)
    }

    private fun isSkillRoot(path: Path): Boolean = path.fileName.toString() == "SKILL.md" && Files.isRegularFile(path)

    private fun isMarkdown(path: Path): Boolean = Files.isRegularFile(path) && path.fileName.toString().endsWith(".md")

    private fun extensions(path: Path, children: List<Path>): List<Path> {
        val own = extensionEntries(path)
        if (own.isNotEmpty()) return own
        return children.flatMap {
            when {
                Files.isDirectory(it) -> extensionEntries(it)

                Files.isRegularFile(it) && it.fileName.toString().let { name ->
                    name.endsWith(".ts") || name.endsWith(".js")
                } -> listOf(it)

                else -> emptyList()
            }
        }
    }

    private fun extensionEntries(path: Path): List<Path> {
        val manifest = text(path.resolve("package.json"))?.let(::resourceJson)
        val entries = ((manifest?.get("pi") as? JsonObject)?.get("extensions") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .asSequence().mapNotNull { entry -> resolve(path, entry) }
            .filter { safe(it) && Files.isRegularFile(it) }.take(MAX_ENTRIES).toList()
        if (entries.isNotEmpty()) return entries
        return listOf("index.ts", "index.js").map(path::resolve)
            .firstOrNull { safe(it) && Files.isRegularFile(it) }?.let(::listOf).orEmpty()
    }

    /** [entry] of a project's manifest under [path]; an entry that is not a valid path here is skipped. */
    fun resolve(path: Path, entry: String): Path? = try {
        path.resolve(entry).normalize()
    } catch (e: InvalidPathException) {
        log.w(e.redactedFailure()) { "Pi extension entry is not a valid path" }
        skip()
        null
    }

    private fun children(path: Path): List<Path> = try {
        Files.newDirectoryStream(path).use { stream ->
            val result = mutableListOf<Path>()
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val child = iterator.next()
                if (!reserve(child)) break
                result.add(child)
            }
            result.sortedBy { it.fileName.toString() }
        }
    } catch (e: IOException) {
        log.w(e.redactedFailure()) { "Pi resource folder not readable" }
        skip()
        emptyList()
    }

    private fun ignores(path: Path): List<IgnoreRule> = listOf(".gitignore", ".ignore", ".fdignore").flatMap { name ->
        text(path.resolve(name))?.lineSequence()?.map { it.trimEnd() }
            ?.filter { it.isNotBlank() && !it.startsWith('#') }
            ?.map { IgnoreRule(path, it.removePrefix("!"), it.startsWith('!')) }?.toList().orEmpty()
    }

    private fun isIgnored(path: Path, rules: List<IgnoreRule>): Boolean {
        var isIgnored = false
        rules.forEach { rule ->
            val relative = rule.base.relativize(path).posix()
            val pattern = rule.pattern.removePrefix("/").removeSuffix("/")
            val isMatched = if ('/' in pattern || rule.pattern.startsWith('/')) {
                piResourceGlob(pattern, relative, budget)
            } else {
                relative.split('/').any { piResourceGlob(pattern, it, budget) }
            }
            if (isMatched && (!rule.pattern.endsWith('/') || Files.isDirectory(path))) isIgnored = !rule.isNegated
        }
        return isIgnored
    }

    private data class Discovery(
        val start: Path,
        val kind: ResourceKind,
        val isAutomatic: Boolean,
        val isAgents: Boolean,
    )

    private data class IgnoreRule(val base: Path, val pattern: String, val isNegated: Boolean)

    private companion object {
        const val MAX_ENTRIES = 4096
        const val MAX_DEPTH = 32
        const val MAX_FILE_BYTES = 64 * 1024
        const val MAX_READ_BYTES = 1024 * 1024
    }
}

/** Pi ignores malformed resource manifests/settings and falls back to directory discovery. */
internal fun resourceJson(text: String): JsonObject? = try {
    Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
} catch (e: SerializationException) {
    log.w(e.redactedFailure()) { "resource metadata is not valid JSON" }
    null
}

private val log = Log.tag("PiResourceFiles")
