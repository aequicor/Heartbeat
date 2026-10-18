package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.nio.file.FileSystems
import java.nio.file.Path

/** Same model-facing operations for every engine; no operation opens the physical project. */
internal fun virtualCodingTools(workspace: VirtualWorkspace, expectedRevision: Long? = null): List<CodingTool> = listOf(
    virtualTool(
        workspace,
        "read_file",
        "Read the current virtual project file",
        listOf("path"),
        listOf("offset", "limit"),
    ),
    virtualTool(workspace, "list_dir", "List the current virtual directory", emptyList(), listOf("path")),
    virtualTool(workspace, "glob", "Find virtual project files with a glob", listOf("pattern"), listOf("path")),
    virtualTool(
        workspace,
        "grep",
        "Search the current virtual project text",
        listOf("pattern"),
        listOf("path", "glob", "ignore_case"),
    ),
    virtualTool(workspace, "write_file", "Create or overwrite a virtual project file", listOf("path", "content")),
    virtualTool(
        workspace,
        "edit_file",
        "Replace an exact fragment in a virtual file",
        listOf("path", "old_string", "new_string"),
        listOf("replace_all"),
    ),
    virtualTool(workspace, "delete_file", "Delete a virtual project file", listOf("path")),
    virtualTool(
        workspace,
        "move_file",
        "Move a virtual file without overwriting a destination",
        listOf("path", "destination"),
    ),
).map { tool ->
    if (expectedRevision == null || !tool.isMutating) {
        tool
    } else {
        object : CodingTool by tool {
            override suspend fun run(args: JsonObject): AgentToolResult =
                AgentToolResult(virtualExecute(workspace, tool.descriptor.name, args, expectedRevision))
        }
    }
}

private val virtualFileLog = Log.tag("VirtualCodingTools")
internal val virtualMutations = setOf("write_file", "edit_file", "delete_file", "move_file")

private fun virtualTool(
    workspace: VirtualWorkspace,
    name: String,
    description: String,
    required: List<String>,
    optional: List<String> = emptyList(),
): CodingTool = object : CodingTool {
    override val descriptor = ToolDescriptor(
        name,
        description,
        required.map(::virtualParameter),
        optional.map(::virtualParameter),
    )
    override val isMutating = name in virtualMutations
    override suspend fun run(args: JsonObject): AgentToolResult = try {
        AgentToolResult(virtualExecute(workspace, name, args))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        virtualFileLog.w(IllegalStateException("VirtualFileOperationFailed (${error::class.simpleName.orEmpty()})")) {
            "Virtual project tool refused name=$name"
        }
        AgentToolResult("Virtual file operation failed: ${error.message.orEmpty().take(VIRTUAL_ERROR_CHARS)}", true)
    }
}

private fun virtualParameter(name: String) = ToolParameterDescriptor(
    name,
    name,
    when (name) {
        "offset", "limit" -> ToolParameterType.Integer
        "ignore_case", "replace_all" -> ToolParameterType.Boolean
        else -> ToolParameterType.String
    },
)

private const val VIRTUAL_MAX_LINES = 2_000
private const val VIRTUAL_DEFAULT_LINES = 500
private const val VIRTUAL_MAX_LINE_CHARS = 4_000
private const val VIRTUAL_MAX_MATCHES = 500
private const val VIRTUAL_ERROR_CHARS = 200

private suspend fun virtualExecute(
    workspace: VirtualWorkspace,
    name: String,
    args: JsonObject,
    expectedRevision: Long? = null,
): String {
    val revision = expectedRevision ?: workspace.revision()
    check(workspace.revision() == revision) { "SandboxRevisionChanged" }
    val path = args.argText("path").trimEnd('/').let { if (it == ".") "" else it }
    return when (name) {
        "read_file" -> virtualRead(workspace, path, args)
        "list_dir" -> virtualList(workspace, path)
        "glob" -> virtualGlob(workspace, path, args)
        "grep" -> virtualGrep(workspace, path, args)
        else -> "Virtual changes saved; revision=${virtualChange(workspace, name, path, args, revision)}"
    }
}

private suspend fun virtualRead(workspace: VirtualWorkspace, path: String, args: JsonObject): String {
    val text = requireNotNull(workspace.read(path)) { "FileNotFound" }
    val offset = (args.argInt("offset") ?: 1).coerceAtLeast(1)
    val limit = (args.argInt("limit") ?: VIRTUAL_DEFAULT_LINES).coerceIn(1, VIRTUAL_MAX_LINES)
    return text.lines().drop(offset - 1).take(limit).mapIndexed { index, line ->
        "${offset + index}\t${line.take(VIRTUAL_MAX_LINE_CHARS)}"
    }.joinToString("\n")
}

private suspend fun virtualList(workspace: VirtualWorkspace, path: String): String =
    workspace.paths().filter { path.isEmpty() || it.startsWith("$path/") }.map { file ->
        val relative = if (path.isEmpty()) file else file.removePrefix("$path/")
        if ('/' in relative) relative.substringBefore('/') + "/" else relative
    }.distinct().sorted().take(VIRTUAL_MAX_LINES).joinToString("\n")

private suspend fun virtualGlob(workspace: VirtualWorkspace, path: String, args: JsonObject): String {
    val matcher = FileSystems.getDefault().getPathMatcher("glob:${args.argText("pattern")}")
    return workspace.paths().filter { (path.isEmpty() || it.startsWith("$path/")) && matcher.matches(Path.of(it)) }
        .take(VIRTUAL_MAX_LINES).joinToString("\n")
}

private suspend fun virtualGrep(workspace: VirtualWorkspace, path: String, args: JsonObject): String {
    val options = if (args.argFlag("ignore_case") == true) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val regex = Regex(args.argText("pattern"), options)
    val glob = args.argText(
        "glob",
    ).takeIf { it.isNotEmpty() }?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
    val selected = workspace.paths().filter { file ->
        val inDirectory = path.isEmpty() || file == path || file.startsWith("$path/")
        inDirectory && (glob == null || glob.matches(Path.of(file)))
    }
    val found = mutableListOf<String>()
    workspace.scanTextFiles(selected) { file, text ->
        text.lineSequence().forEachIndexed { index, line ->
            if (found.size < VIRTUAL_MAX_MATCHES && regex.containsMatchIn(line)) {
                found += "$file:${index + 1}: ${line.take(VIRTUAL_MAX_LINE_CHARS)}"
            }
        }
        found.size < VIRTUAL_MAX_MATCHES
    }
    return found.joinToString("\n").ifEmpty { "No matches" }
}

private suspend fun virtualChange(
    workspace: VirtualWorkspace,
    name: String,
    path: String,
    args: JsonObject,
    revision: Long,
): Long = if (name == "move_file") {
    workspace.move(revision, path, args.argText("destination"))
} else {
    val content = when (name) {
        "write_file" -> args.argText("content")

        "delete_file" -> {
            check(path in workspace.paths()) { "FileNotFound" }
            null
        }

        "edit_file" -> virtualReplacement(requireNotNull(workspace.read(path)) { "FileNotFound" }, args)

        else -> error("UnsupportedVirtualTool")
    }
    workspace.change(revision, listOf(VirtualFileChange(path, content)))
}

private fun virtualReplacement(content: String, args: JsonObject): String {
    val old = args.argText("old_string")
    require(old.isNotEmpty()) { "EmptyOldString" }
    val first = content.indexOf(old)
    require(first >= 0 && (args.argFlag("replace_all") == true || content.indexOf(old, first + 1) < 0)) {
        "AmbiguousReplacement"
    }
    return content.replace(old, args.argText("new_string"))
}
