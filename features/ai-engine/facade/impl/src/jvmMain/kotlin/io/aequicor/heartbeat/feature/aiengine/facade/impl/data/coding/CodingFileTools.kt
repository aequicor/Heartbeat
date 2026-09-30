package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.PatternSyntaxException
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * File tools of a coding session confined to [root]. Reads run freely; [write_file] and [edit_file] are mutating
 * and need the session's approval. Tool output is sent to the provider, never logged: logs name the tool only.
 */
internal fun codingFileTools(root: ProjectRoot, io: CoroutineDispatcher): List<CodingTool> = listOf(
    ReadFile(root, io),
    ListDirectory(root, io),
    GlobFiles(root, io),
    GrepFiles(root, io),
    WriteFile(root, io),
    EditFile(root, io),
)

private val fileLog = Log.tag("CodingFileTools")

private abstract class FileTool(protected val root: ProjectRoot, private val io: CoroutineDispatcher) : CodingTool {
    final override suspend fun run(args: JsonObject): AgentToolResult = try {
        withContext(io) { execute(args) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutsideProjectException) {
        fileLog.w(e) { "${descriptor.name} refused a path outside the project" }
        AgentToolResult(e.message.orEmpty(), true)
    } catch (e: IOException) {
        fileLog.w(e) { "${descriptor.name} failed: ${e::class.simpleName.orEmpty()}" }
        AgentToolResult("${e::class.simpleName.orEmpty()}: ${e.message.orEmpty()}", true)
    } catch (e: UncheckedIOException) {
        fileLog.w(e) { "${descriptor.name} failed while listing" }
        AgentToolResult("IOException: ${e.cause?.message.orEmpty()}", true)
    } catch (e: InvalidPathException) {
        fileLog.w(e) { "${descriptor.name} rejected an invalid path" }
        AgentToolResult("Invalid path: ${e.message.orEmpty()}", true)
    }

    protected abstract suspend fun execute(args: JsonObject): AgentToolResult

    protected fun failed(message: String) = AgentToolResult(message, true)

    protected fun ok(text: String) = AgentToolResult(text, false)
}

private class ReadFile(root: ProjectRoot, io: CoroutineDispatcher) : FileTool(root, io) {
    override val descriptor = ToolDescriptor(
        "read_file",
        "Read a text file of the project. Returns numbered lines; use offset/limit for large files.",
        listOf(path("File path relative to the project root")),
        listOf(
            ToolParameterDescriptor("offset", "First line to return, 1-based", ToolParameterType.Integer),
            ToolParameterDescriptor("limit", "Maximum line count, up to $MAX_READ_LINES", ToolParameterType.Integer),
        ),
    )

    override suspend fun execute(args: JsonObject): AgentToolResult {
        val file = root.resolve(args.argText("path"))
        if (!Files.isRegularFile(file)) return failed("Not a file: ${root.relative(file)}")
        val text = readText(file) ?: return failed("Binary or non UTF-8 file: ${root.relative(file)}")
        val lines = text.lines()
        val from = (args.argInt("offset") ?: 1).coerceAtLeast(1)
        val count = (args.argInt("limit") ?: MAX_READ_LINES).coerceIn(1, MAX_READ_LINES)
        val shown = lines.drop(from - 1).take(count)
        val body = shown.withIndex().joinToString("\n") { (index, line) ->
            "${from + index}\t${line.take(MAX_LINE_CHARS)}"
        }
        val rest = lines.size - (from - 1 + shown.size)
        return ok(if (rest > 0) "$body\n… $rest more lines, continue with offset=${from + shown.size}" else body)
    }
}

private class ListDirectory(root: ProjectRoot, io: CoroutineDispatcher) : FileTool(root, io) {
    override val descriptor = ToolDescriptor(
        "list_dir",
        "List entries of a project directory; directories end with '/'.",
        emptyList(),
        listOf(path("Directory relative to the project root, default is the root")),
    )

    override suspend fun execute(args: JsonObject): AgentToolResult {
        val directory = root.resolve(args.argText("path"))
        if (!Files.isDirectory(directory)) return failed("Not a directory: ${root.relative(directory)}")
        val entries = Files.list(directory).use { stream ->
            stream.map { if (it.isDirectory()) "${it.name}/" else it.name }.sorted().toList()
        }
        val shown = entries.take(MAX_ENTRIES).joinToString("\n")
        return ok(if (entries.size > MAX_ENTRIES) "$shown\n… ${entries.size - MAX_ENTRIES} more" else shown)
    }
}

private class GlobFiles(root: ProjectRoot, io: CoroutineDispatcher) : FileTool(root, io) {
    override val descriptor = ToolDescriptor(
        "glob",
        "Find project files whose relative path matches a glob such as **/*.kt.",
        listOf(ToolParameterDescriptor("pattern", "Glob over project-relative paths", ToolParameterType.String)),
        listOf(path("Directory to search in, default is the root")),
    )

    override suspend fun execute(args: JsonObject): AgentToolResult {
        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:${args.argText("pattern")}")
        } catch (e: IllegalArgumentException) {
            fileLog.w(e) { "glob rejected a pattern" }
            return failed("Invalid glob: ${e.message.orEmpty()}")
        }
        val found = mutableListOf<String>()
        walk(root.resolve(args.argText("path"))) { file ->
            val relative = root.relative(file)
            if (matcher.matches(Path.of(relative))) found += relative
            found.size <= MAX_ENTRIES
        }
        if (found.isEmpty()) return ok("No files found")
        val shown = found.take(MAX_ENTRIES).joinToString("\n")
        return ok(if (found.size > MAX_ENTRIES) "$shown\n… more files, narrow the pattern" else shown)
    }
}

private class GrepFiles(root: ProjectRoot, io: CoroutineDispatcher) : FileTool(root, io) {
    override val descriptor = ToolDescriptor(
        "grep",
        "Search project text files with a regular expression; returns path:line: text.",
        listOf(ToolParameterDescriptor("pattern", "Regular expression", ToolParameterType.String)),
        listOf(
            path("Directory or file to search in, default is the root"),
            ToolParameterDescriptor(
                "glob",
                "Only files whose relative path matches this glob",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor("ignore_case", "Case-insensitive search", ToolParameterType.Boolean),
        ),
    )

    override suspend fun execute(args: JsonObject): AgentToolResult {
        val regex = try {
            val options = if (args.argFlag("ignore_case") == true) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex(args.argText("pattern"), options)
        } catch (e: PatternSyntaxException) {
            fileLog.w(e) { "grep rejected a pattern" }
            return failed("Invalid regular expression: ${e.description}")
        }
        val filter = try {
            args.argText("glob").takeIf { it.isNotBlank() }?.let {
                FileSystems.getDefault().getPathMatcher("glob:$it")
            }
        } catch (e: IllegalArgumentException) {
            fileLog.w(e) { "grep rejected a glob" }
            return failed("Invalid glob: ${e.message.orEmpty()}")
        }
        val matches = mutableListOf<String>()
        walk(root.resolve(args.argText("path"))) { file ->
            val relative = root.relative(file)
            if (filter == null || filter.matches(Path.of(relative))) {
                readText(file)?.lineSequence()?.forEachIndexed { index, line ->
                    if (regex.containsMatchIn(line) && matches.size <= MAX_MATCHES) {
                        matches += "$relative:${index + 1}: ${line.trim().take(MAX_LINE_CHARS)}"
                    }
                }
            }
            matches.size <= MAX_MATCHES
        }
        if (matches.isEmpty()) return ok("No matches")
        val shown = matches.take(MAX_MATCHES).joinToString("\n")
        return ok(if (matches.size > MAX_MATCHES) "$shown\n… more matches, narrow the search" else shown)
    }
}

private class WriteFile(root: ProjectRoot, io: CoroutineDispatcher) : FileTool(root, io) {
    override val descriptor = ToolDescriptor(
        "write_file",
        "Create or overwrite a project file with the given content. Prefer edit_file for changes to existing files.",
        listOf(
            path("File path relative to the project root"),
            ToolParameterDescriptor("content", "Full new file content", ToolParameterType.String),
        ),
        emptyList(),
    )
    override val isMutating = true

    override fun target(args: JsonObject) = "Записать файл ${args.argText("path")}"

    override fun details(args: JsonObject) = preview(args.argText("content"))

    override suspend fun execute(args: JsonObject): AgentToolResult {
        val file = root.resolve(args.argText("path"))
        if (Files.isDirectory(file)) return failed("Is a directory: ${root.relative(file)}")
        val isNew = !Files.exists(file)
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, args.argText("content"))
        fileLog.i { "write_file ${if (isNew) "created" else "overwrote"} a project file" }
        return ok("${if (isNew) "Created" else "Overwrote"} ${root.relative(file)}")
    }
}

private class EditFile(root: ProjectRoot, io: CoroutineDispatcher) : FileTool(root, io) {
    override val descriptor = ToolDescriptor(
        "edit_file",
        "Replace an exact text fragment of a project file. old_string must match exactly once unless replace_all.",
        listOf(
            path("File path relative to the project root"),
            ToolParameterDescriptor("old_string", "Exact text to replace", ToolParameterType.String),
            ToolParameterDescriptor("new_string", "Replacement text", ToolParameterType.String),
        ),
        listOf(ToolParameterDescriptor("replace_all", "Replace every occurrence", ToolParameterType.Boolean)),
    )
    override val isMutating = true

    override fun target(args: JsonObject) = "Изменить файл ${args.argText("path")}"

    override fun details(args: JsonObject) =
        "− ${preview(args.argText("old_string"))}\n+ ${preview(args.argText("new_string"))}"

    override suspend fun execute(args: JsonObject): AgentToolResult {
        val file = root.resolve(args.argText("path"))
        val old = args.argText("old_string")
        val text = if (Files.isRegularFile(file)) readText(file) else null
        val count = if (text == null || old.isEmpty()) 0 else occurrences(text, old)
        val isAll = args.argFlag("replace_all") == true
        val refusal = when {
            !Files.isRegularFile(file) -> "Not a file: ${root.relative(file)}"
            text == null -> "Binary or non UTF-8 file: ${root.relative(file)}"
            old.isEmpty() -> "old_string is empty"
            count == 0 -> "old_string not found in ${root.relative(file)}"
            count > 1 && !isAll -> "old_string occurs $count times; add context or set replace_all"
            else -> null
        }
        if (refusal != null || text == null) return failed(refusal.orEmpty())
        val updated = if (isAll) {
            text.replace(old, args.argText("new_string"))
        } else {
            text.replaceFirst(old, args.argText("new_string"))
        }
        Files.writeString(file, updated)
        fileLog.i { "edit_file replaced $count fragment(s)" }
        return ok("Edited ${root.relative(file)}: $count replacement(s)")
    }
}

private fun occurrences(text: String, fragment: String): Int {
    var count = 0
    var index = text.indexOf(fragment)
    while (index >= 0) {
        count++
        index = text.indexOf(fragment, index + fragment.length)
    }
    return count
}

private fun path(description: String) = ToolParameterDescriptor("path", description, ToolParameterType.String)

/** UTF-8 text of [file], or null for binary, non UTF-8 or oversized files. */
private fun readText(file: Path): String? {
    if (Files.size(file) > MAX_FILE_BYTES) return null
    val bytes = Files.readAllBytes(file)
    if (bytes.any { it == 0.toByte() }) return null
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        fileLog.w(e) { "Skipping a non UTF-8 file" }
        null
    }
}

/**
 * Visits regular files under [start] (or [start] itself), skipping VCS and build caches and not following links;
 * stops when [visit] returns false.
 */
private suspend fun walk(start: Path, visit: (Path) -> Boolean) {
    val context = currentCoroutineContext()
    if (Files.isRegularFile(start)) {
        visit(start)
        return
    }
    Files.walkFileTree(
        start,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                return if (dir != start && dir.name in SKIPPED_DIRECTORIES) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (!attrs.isRegularFile || visit(file)) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                fileLog.w(exc) { "Skipping an unreadable project entry" }
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun preview(text: String): String = if (text.length <= PREVIEW_CHARS) {
    text
} else {
    "${text.take(
        PREVIEW_CHARS,
    )}\n… ещё ${text.length - PREVIEW_CHARS} символов"
}

private val SKIPPED_DIRECTORIES = setOf(".git", ".gradle", ".idea", "node_modules", "build", ".kotlin")
private const val MAX_READ_LINES = 2000
private const val MAX_LINE_CHARS = 2000
private const val MAX_ENTRIES = 500
private const val MAX_MATCHES = 200
private const val MAX_FILE_BYTES = 2L * 1024 * 1024
private const val PREVIEW_CHARS = 4000
