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
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * A bounded, confined view of resource files; links are followed only within the root and never recursively twice.
 * An entry that cannot be read or named is skipped and marks the view incomplete, as Pi skips it too. Ignore rules are
 * capped and matched within the read's shared [globs] allowance, since a project supplies them.
 */
internal class PiResourceFiles(private val root: Path, private val globs: ResourceGlobs) {
    var isTruncated: Boolean = false
        private set

    /** Entries skipped because they could not be read or are not valid paths. */
    var skipped: Int = 0
        private set
    private val inspected = mutableSetOf<Path>()
    private var readBytes = 0
    private var ignoreRules = 0
    private var failures = 0

    fun safe(path: Path): Boolean = reserve(path) && path.normalize().startsWith(root) && try {
        isInside(root, path, PI_MAX_LINKS)
    } catch (e: IOException) {
        if (skip()) log.w(e.redactedFailure()) { LINK_FAILURE } else log.v { LINK_FAILURE }
        false
    }

    /**
     * Records an entry Pi would not load either. True for the read's first failure, which the caller logs at WARN with
     * its cause; the rest go to VERBOSE and the inventory sums them up, so a hostile project cannot flood the log.
     */
    fun skip(): Boolean {
        isTruncated = true
        skipped++
        return failures++ == 0
    }

    /** Pi ignores malformed resource manifests and settings and falls back to directory discovery. */
    fun json(text: String): JsonObject? = try {
        Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
    } catch (e: SerializationException) {
        if (failures++ == 0) log.w(e.redactedFailure()) { JSON_FAILURE } else log.v { JSON_FAILURE }
        null
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
            if (skip()) log.w(e.redactedFailure()) { FILE_FAILURE } else log.v { FILE_FAILURE }
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
            if (skip()) log.w(e.redactedFailure()) { FOLDER_FAILURE } else log.v { FOLDER_FAILURE }
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
        val manifest = text(path.resolve("package.json"))?.let(::json)
        val entries = ((manifest?.get("pi") as? JsonObject)?.get("extensions") as? JsonArray).orEmpty()
            .asSequence().take(MAX_ENTRIES)
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .mapNotNull { entry -> resolve(path, entry) }
            .filter { safe(it) && Files.isRegularFile(it) }.take(MAX_ENTRIES).toList()
        if (entries.isNotEmpty()) return entries
        return listOf("index.ts", "index.js").map(path::resolve)
            .firstOrNull { safe(it) && Files.isRegularFile(it) }?.let(::listOf).orEmpty()
    }

    /** [entry] of a package manifest under [path]; an entry that is not a valid path here is skipped. */
    private fun resolve(path: Path, entry: String): Path? = try {
        path.resolve(entry).normalize()
    } catch (e: InvalidPathException) {
        if (skip()) log.w(e.redactedFailure()) { ENTRY_FAILURE } else log.v { ENTRY_FAILURE }
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
        if (skip()) log.w(e.redactedFailure()) { FOLDER_FAILURE } else log.v { FOLDER_FAILURE }
        emptyList()
    } catch (e: DirectoryIteratorException) {
        // Thrown by the stream's iterator when reading the folder fails midway; its cause is the IOException.
        if (skip()) log.w(e.cause?.redactedFailure()) { FOLDER_FAILURE } else log.v { FOLDER_FAILURE }
        emptyList()
    }

    private fun ignores(path: Path): List<IgnoreRule> = listOf(".gitignore", ".ignore", ".fdignore").flatMap { name ->
        val lines = text(path.resolve(name))?.lineSequence()?.map { it.trimEnd() }
            ?.filter { it.isNotBlank() && !it.startsWith('#') }?.toList().orEmpty()
        val kept = lines.take((MAX_IGNORE_RULES - ignoreRules).coerceAtLeast(0))
        ignoreRules += kept.size
        if (kept.size < lines.size) isTruncated = true
        kept.map { IgnoreRule(path, it.removePrefix("!"), it.startsWith('!')) }
    }

    /** Every rule evaluation is charged, so rules stop applying once the read's allowance is spent. */
    private fun isIgnored(path: Path, rules: List<IgnoreRule>): Boolean {
        var isIgnored = false
        for (rule in rules) {
            if (!globs.spend()) break
            val relative = rule.base.relativize(path).posix()
            val pattern = rule.pattern.removePrefix("/").removeSuffix("/")
            val isMatched = if ('/' in pattern || rule.pattern.startsWith('/')) {
                globs.matches(pattern, relative)
            } else {
                relative.split('/').any { globs.matches(pattern, it) }
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
        const val MAX_IGNORE_RULES = 1024
        const val LINK_FAILURE = "Pi resource link not resolved"
        const val FILE_FAILURE = "Pi resource file not readable"
        const val FOLDER_FAILURE = "Pi resource folder not readable"
        const val ENTRY_FAILURE = "Pi extension entry is not a valid path"
        const val JSON_FAILURE = "resource metadata is not valid JSON"
    }
}

private val log = Log.tag("PiResourceFiles")
