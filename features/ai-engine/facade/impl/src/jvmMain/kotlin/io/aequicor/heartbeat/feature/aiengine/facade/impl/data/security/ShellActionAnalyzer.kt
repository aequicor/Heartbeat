package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionResourceKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeShellDialect
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeShellExecution
import io.aequicor.heartbeat.feature.aiengine.facade.api.POWER_SHELL_UTF8_INITIALIZATION
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding.ProjectRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * Structural parsing precedes semantics. Only complete implemented command contracts produce evidence:
 * bounded PowerShell directory listings and name projections, literal POSIX system listings and cmd dir.
 * Unrecognized syntax, effects or launchers retain manual confirmation; this is not a general shell interpreter.
 * Resource identities are checked again by the gate. This preflight does not create an OS sandbox or lock the
 * filesystem against unrelated processes replacing resources after the last check.
 */
internal class ShellActionAnalyzer(private val io: CoroutineDispatcher) {
    private val log = Log.tag("ShellActionAnalyzer")

    suspend fun analyze(
        root: ProjectRoot,
        directory: Path,
        command: String,
        shell: NativeShellExecution,
    ): ActionAnalysis = try {
        withContext(io) { inspect(root, directory, command, shell) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Shell action evidence unavailable" }
        unknown()
    }

    private suspend fun inspect(
        root: ProjectRoot,
        directory: Path,
        command: String,
        shell: NativeShellExecution,
    ): ActionAnalysis {
        if (shell.environmentBinding.isBlank() || !hasLiteralLauncher(shell)) return unknown()
        if (directory.toString() != directory.toString().trim()) return unknown()
        val cwd = root.canonical(directory.toString())
        val structure = if (shell.dialect == NativeShellDialect.PowerShell) {
            PowerShellStructure(Path.of(shell.executable), io).parse(command)
        } else {
            parseShellStructure(command, ShellDialect.valueOf(shell.dialect.name))
        }
        if (structure !is ShellParseResult.Parsed) return unknown()
        val paths = resolvePaths(root, cwd, structure, shell.dialect) ?: return unknown()
        if (paths.isEmpty()) return unknown()
        val executable = Path.of(shell.executable).toRealPath()
        val binding = buildString {
            append(command).append('\n').append(shell.dialect).append(shell.argumentPrefix)
            append(shell.initialization).append(shell.environmentBinding)
            append(identity(executable)).append(identity(root.path)).append(identity(cwd))
            paths.forEach { append(identity(it)) }
        }.encodeUtf8().sha256().hex()
        return ActionAnalysis.Known(
            ActionEvidence(
                "${shell.dialect} directory inspection: $command",
                paths.distinct().map { ActionResource(ActionResourceKind.FileSystem, it.toString(), true) },
                setOf(ActionEffect.Read),
                binding,
            ),
        )
    }
}

private suspend fun resolvePaths(
    root: ProjectRoot,
    cwd: Path,
    structure: ShellParseResult.Parsed,
    dialect: NativeShellDialect,
): List<Path>? {
    val paths = mutableListOf<Path>()
    for (pipeline in structure.statements) {
        val raw = pipelinePaths(pipeline, dialect) ?: return null
        val depth = if (dialect == NativeShellDialect.PowerShell) powerShellDepth(pipeline) else 0
        for (path in raw) {
            if (!hasLiteralPath(path, dialect)) return null
            val physical = root.canonical(cwd.resolve(path).toString())
            if (!Files.isDirectory(physical)) return null
            paths.addAll(verifiedTree(root, physical, depth) ?: return null)
        }
    }
    return paths
}

private fun pipelinePaths(pipeline: ShellPipeline, dialect: NativeShellDialect): List<String>? {
    if (pipeline.commands.any { it.redirects.isNotEmpty() }) return null
    return when (dialect) {
        NativeShellDialect.PowerShell -> powerShellPaths(pipeline)
        NativeShellDialect.Posix, NativeShellDialect.Bash -> posixPaths(pipeline)
        NativeShellDialect.Cmd -> cmdPaths(pipeline)
    }
}

private fun hasLiteralPath(path: String, dialect: NativeShellDialect): Boolean {
    if (path.isBlank() || path != path.trim() || path.startsWith('~')) return false
    if (path.any { it in "*?[]\u0000" } || path.startsWith("\\\\") || path.startsWith("//")) return false
    // PowerShell provider names and drive-relative paths have shell state that java.nio.file cannot attest.
    return dialect != NativeShellDialect.PowerShell || ':' !in path || Path.of(path).isAbsolute
}

/** Resolve each directory before opening it. Never follow a link during recursive scope discovery. */
private suspend fun verifiedTree(root: ProjectRoot, start: Path, depth: Int): List<Path>? =
    DirectoryVerification(root).inspect(start, depth)

private class DirectoryVerification(private val root: ProjectRoot) {
    private val pending = ArrayDeque<Pair<Path, Int>>()
    private var entries = 0

    suspend fun inspect(start: Path, depth: Int): List<Path>? {
        val directories = mutableListOf<Path>()
        pending.add(start to depth)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, remaining) = pending.removeFirst()
            if (!verifyDirectory(directory, remaining)) return null
            directories.add(directory)
        }
        return directories
    }

    private suspend fun verifyDirectory(directory: Path, remaining: Int): Boolean {
        if (root.canonical(directory.toString()) != directory || Files.isSymbolicLink(directory)) return false
        Files.newDirectoryStream(directory).use { children ->
            for (child in children) {
                currentCoroutineContext().ensureActive()
                val attributes = attributes(child) ?: return false
                if (remaining > 0 && attributes.isDirectory) pending.add(child to remaining - 1)
            }
        }
        return true
    }

    private fun attributes(child: Path): BasicFileAttributes? {
        if (++entries > MAX_INSPECTED_ENTRIES) return null
        return Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .takeUnless { it.isSymbolicLink || it.isOther }
    }
}

private fun powerShellDepth(pipeline: ShellPipeline): Int {
    val words = pipeline.commands.first().words
    val index = words.indexOfFirst { it.equals("-depth", ignoreCase = true) }
    return if (index < 0) 0 else words[index + 1].toInt()
}

private fun hasLiteralLauncher(shell: NativeShellExecution): Boolean {
    if (!Path.of(shell.executable).isAbsolute) return false
    if (shell.initialization.isNotEmpty() &&
        (shell.dialect != NativeShellDialect.PowerShell || shell.initialization != POWER_SHELL_UTF8_INITIALIZATION)
    ) {
        return false
    }
    return when (shell.dialect) {
        NativeShellDialect.PowerShell -> shell.argumentPrefix.map { it.lowercase(Locale.ROOT) } ==
            listOf("-nologo", "-noprofile", "-noninteractive", "-command")

        NativeShellDialect.Posix -> shell.executable == "/bin/sh" && shell.argumentPrefix == listOf("-c")

        NativeShellDialect.Bash -> shell.argumentPrefix == listOf("--noprofile", "--norc", "-c")

        NativeShellDialect.Cmd -> shell.argumentPrefix.map { it.lowercase(Locale.ROOT) } == listOf("/d", "/s", "/c")
    }
}

private fun powerShellPaths(pipeline: ShellPipeline): List<String>? {
    if (pipeline.commands.size !in 1..2) return null
    val first = pipeline.commands.first().words
    if (first.first().lowercase(Locale.ROOT) !in CHILD_ITEM_NAMES) return null
    if (pipeline.commands.size == 2 && !isNameProjection(pipeline.commands.last().words)) return null
    return PowerShellPaths(first).parse()
}

private fun isNameProjection(words: List<String>): Boolean {
    val projection = words.map { it.lowercase(Locale.ROOT) }
    return projection.firstOrNull() in SELECT_NAMES && projection.drop(1) in NAME_PROJECTIONS
}

private class PowerShellPaths(private val words: List<String>) {
    private var path: String? = null
    private var isRecursive = false
    private var depth: Int? = null
    private val seen = mutableSetOf<String>()

    fun parse(): List<String>? {
        var index = 1
        while (index < words.size) {
            index = consume(index) ?: return null
        }
        return if (isRecursive && depth == null) null else listOf(path ?: ".")
    }

    private fun consume(index: Int): Int? {
        val word = words[index]
        val option = word.lowercase(Locale.ROOT)
        if (word.startsWith('-') && !seen.add(option)) return null
        return when (option) {
            "-directory", "-file", "-force", "-name" -> index + 1

            "-recurse" -> {
                isRecursive = true
                index + 1
            }

            "-depth" -> consumeDepth(index)

            "-path", "-literalpath" -> consumePath(index + 1)

            else -> if (word.startsWith('-')) null else consumePath(index)
        }
    }

    private fun consumeDepth(index: Int): Int? {
        val value = words.getOrNull(index + 1)?.toIntOrNull()
        return if (value != null && value in 0..MAX_RECURSION_DEPTH) {
            depth = value
            index + 2
        } else {
            null
        }
    }

    private fun consumePath(index: Int): Int? {
        if (path != null) return null
        path = words.getOrNull(index)
        return if (path == null) null else index + 1
    }
}

private fun posixPaths(pipeline: ShellPipeline): List<String>? {
    if (pipeline.commands.size != 1) return null
    val words = pipeline.commands.single().words
    if (words == listOf("pwd")) return listOf(".")
    // A bare ls could resolve to project code through PATH. Only these explicit system executables are covered.
    if (words.first() !in setOf("/bin/ls", "/usr/bin/ls")) return null
    val paths = mutableListOf<String>()
    var hasEndedOptions = false
    for (word in words.drop(1)) {
        when {
            word == "--" && !hasEndedOptions -> hasEndedOptions = true

            word.startsWith('-') && !hasEndedOptions -> {
                if (word.length < 2 || word.drop(1).any { it !in "1aldAF" }) return null
            }

            else -> paths += word
        }
    }
    return paths.ifEmpty { listOf(".") }
}

private fun cmdPaths(pipeline: ShellPipeline): List<String>? {
    if (pipeline.commands.size != 1) return null
    val words = pipeline.commands.single().words
    if (!words.first().equals("dir", ignoreCase = true)) return null
    val paths = mutableListOf<String>()
    for (word in words.drop(1)) {
        if (word.startsWith('/')) {
            if (word.lowercase(Locale.ROOT) !in setOf("/b", "/a:d")) return null
        } else {
            paths += word
        }
    }
    return paths.ifEmpty { listOf(".") }
}

private fun identity(path: Path): String {
    val stat = Files.readAttributes(path, BasicFileAttributes::class.java)
    return "${path.toRealPath()}:${stat.fileKey()}:${stat.creationTime()}" +
        if (stat.isRegularFile) ":${stat.size()}:${stat.lastModifiedTime()}" else ""
}

private fun unknown(): ActionAnalysis = ActionAnalysis.Unknown("Shell effects or resource scope are not fully verified")
private const val MAX_RECURSION_DEPTH = 8
private const val MAX_INSPECTED_ENTRIES = 4_096
private val CHILD_ITEM_NAMES = setOf(
    "get-childitem",
    "gci",
    "dir",
    "ls",
    "microsoft.powershell.management\\get-childitem",
)
private val SELECT_NAMES = setOf("select-object", "select", "microsoft.powershell.utility\\select-object")
private val NAME_PROJECTIONS = setOf(listOf("-expandproperty", "name"), listOf("-expandproperty", "fullname"))
