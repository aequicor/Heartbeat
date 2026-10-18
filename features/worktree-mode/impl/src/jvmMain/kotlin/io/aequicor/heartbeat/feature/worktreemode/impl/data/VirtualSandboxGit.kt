package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Git-object operations only: no checkout, source index, hooks or source working files are changed. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class VirtualSandboxGit(private val dispatchers: DispatcherProvider) : VirtualGitObjects {
    private val trees = object : LinkedHashMap<String, Map<String, GitEntry>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, GitEntry>>): Boolean =
            size > 16
    }
    override suspend fun call(
        source: String,
        vararg arguments: String,
        input: ByteArray,
        environment: Map<String, String>,
        allowFailure: Boolean,
    ): GitBytes = withContext(dispatchers.io) {
        val out = Files.createTempFile("sandbox-git-", ".out")
        val err = Files.createTempFile("sandbox-git-", ".err")
        val hooks = Files.createTempDirectory("sandbox-hooks-")
        var process: Process? = null
        try {
            process = ProcessBuilder(
                listOf(
                    "git",
                    "--no-optional-locks",
                    "-c",
                    "core.fsmonitor=false",
                    "-c", "maintenance.auto=false", "-c", "gc.auto=0",
                    "-c",
                    "core.hooksPath=$hooks",
                ) + arguments,
            )
                .directory(Path.of(source).toFile()).redirectOutput(out.toFile()).redirectError(err.toFile())
                .apply {
                    environment().keys.filter { it.startsWith("GIT_") }.forEach { environment().remove(it) }
                    environment().putAll(environment)
                    environment().putAll(identity)
                    environment()["GIT_NO_REPLACE_OBJECTS"] = "1"
                    environment()["GIT_NO_LAZY_FETCH"] = "1"
                }.start()
            process.outputStream.use { it.write(input) }
            check(
                withTimeoutOrNull(TIMEOUT) {
                    while (!process.waitFor(POLL, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
                    true
                } == true,
            ) { "SandboxGitTimedOut" }
            val code = process.exitValue()
            check(allowFailure || code == 0) { "SandboxGitFailed" }
            check(Files.size(out) <= MAX_OUTPUT) { "SandboxFileTooLarge" }
            GitBytes(code, Files.readAllBytes(out))
        } finally {
            process?.let { child ->
                child.descendants().use { tree -> tree.filter { it.isAlive }.forEach { it.destroyForcibly() } }
                if (child.isAlive) child.destroyForcibly()
                child.waitFor(TIMEOUT, TimeUnit.MILLISECONDS)
            }
            cleanupGitTemporary(out)
            cleanupGitTemporary(err)
            cleanupGitTemporary(hooks)
        }
    }

    override suspend fun text(source: String, vararg arguments: String): String = call(source, *arguments).text.trim()

    override suspend fun entries(source: String, commit: String): Map<String, GitEntry> {
        synchronized(trees) { trees[commit]?.let { return it } }
        val entries = call(source, "ls-tree", "-r", "-z", "--full-tree", commit).text.split('\u0000')
            .filter { it.isNotEmpty() }.associate { row ->
                val header = row.substringBefore('\t').split(' ')
                val path = row.substringAfter('\t')
                path to GitEntry(header[0], header[1], header[2])
            }
        synchronized(trees) { trees[commit] = entries }
        return entries
    }

    /** Streaming cat-file batch avoids a process and full tree parse for every searched file. */
    override suspend fun scanTexts(source: String, entries: Map<String, GitEntry>, visit: (String, String) -> Boolean) =
        withContext(dispatchers.io) {
            GitTextBatch(source, MAX_OUTPUT).use { batch ->
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT)
                for ((path, entry) in entries) {
                    currentCoroutineContext().ensureActive()
                    check(System.nanoTime() < deadline) { "SandboxSearchTimeout" }
                    val text = batch.read(entry)
                    if (text != null && !visit(path, text)) break
                }
            }
        }

    override suspend fun edit(source: String, commit: String, edits: Map<String, String?>): String {
        val previous = entries(source, commit)
        val deleted = edits.filterValues { it == null }.keys
        val written = edits.filterValues { it != null }.keys
        validateFilePaths(previous.keys - deleted + written)
        val index = Files.createTempFile("sandbox-index-", ".index")
        Files.delete(index)
        val environment = mapOf("GIT_INDEX_FILE" to index.toString())
        try {
            call(source, "read-tree", commit, environment = environment)
            val updates = buildString {
                edits.entries.sortedBy { it.value != null }.forEach { (path, content) ->
                    if (content == null) {
                        append("0 ${"0".repeat(commit.length)}\t$path\u0000")
                    } else {
                        val blob = call(source, "hash-object", "-w", "--stdin", input = content.encodeToByteArray())
                            .text.trim()
                        val mode = previous[path]?.mode ?: "100644"
                        check(mode in setOf("100644", "100755")) { "SandboxLinkUnsupported" }
                        append("$mode $blob\t$path\u0000")
                    }
                }
            }
            call(
                source,
                "update-index",
                "-z",
                "--index-info",
                input = updates.encodeToByteArray(),
                environment = environment,
            )
            return commit(source, textWithEnvironment(source, environment, "write-tree"), commit)
        } finally {
            Files.deleteIfExists(index)
            Files.deleteIfExists(Path.of("$index.lock"))
        }
    }

    override suspend fun commit(source: String, tree: String, parent: String): String =
        text(source, "commit-tree", "--no-gpg-sign", tree, "-p", parent, "-m", "Sandbox virtual revision")

    override suspend fun move(source: String, commit: String, path: String, destination: String): String {
        val previous = entries(source, commit)
        val entry = requireNotNull(previous[path]) { "FileNotFound" }
        check(destination !in previous) { "DestinationExists" }
        validateFilePaths(previous.keys - path + destination)
        val index = Files.createTempFile("sandbox-index-", ".index")
        Files.delete(index)
        val environment = mapOf("GIT_INDEX_FILE" to index.toString())
        try {
            call(source, "read-tree", commit, environment = environment)
            val updates = "0 ${"0".repeat(commit.length)}\t$path\u0000" +
                "${entry.mode} ${entry.objectId}\t$destination\u0000"
            call(
                source,
                "update-index",
                "-z",
                "--index-info",
                input = updates.encodeToByteArray(),
                environment = environment,
            )
            return commit(source, textWithEnvironment(source, environment, "write-tree"), commit)
        } finally {
            Files.deleteIfExists(index)
            Files.deleteIfExists(Path.of("$index.lock"))
        }
    }

    override suspend fun merge(source: String, base: String, ours: String, theirs: String): GitMerge =
        withContext(dispatchers.io) {
            val directory = Files.createTempDirectory("sandbox-merge-").toRealPath()
            val configuration = Files.createFile(directory.resolve("empty-config"))
            val cleanEnvironment = mapOf(
                "GIT_CONFIG_GLOBAL" to configuration.toString(),
                "GIT_CONFIG_SYSTEM" to configuration.toString(),
                "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_ATTR_NOSYSTEM" to "1",
            )
            try {
                val format = text(source, "rev-parse", "--show-object-format")
                check(format in setOf("sha1", "sha256")) { "SandboxObjectFormatUnsupported" }
                call(
                    directory.toString(),
                    "init",
                    "--bare",
                    "--template=",
                    "--object-format=$format",
                    "repository",
                    environment = cleanEnvironment,
                )
                val objects = text(source, "rev-parse", "--path-format=absolute", "--git-path", "objects")
                val result = call(
                    directory.toString(), "merge-tree", "--write-tree", "--name-only", "-z",
                    "--merge-base=$base", ours, theirs, allowFailure = true,
                    environment = cleanEnvironment + mapOf(
                        "GIT_DIR" to directory.resolve("repository").toString(),
                        "GIT_OBJECT_DIRECTORY" to objects,
                    ),
                )
                check(result.code in 0..1) { "SandboxMergeFailed" }
                val parts = result.text.split('\u0000')
                GitMerge(
                    parts.first(),
                    if (result.code == 0) emptyList() else parts.drop(1).takeWhile { it.isNotEmpty() },
                )
            } finally {
                check(directory.toRealPath() == directory && directory.fileName.toString().startsWith("sandbox-merge-"))
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }

    override suspend fun pin(source: String, id: String, commit: String) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        call(source, "update-ref", "refs/heartbeat/virtual/$id/$commit", commit)
    }

    /** update-index otherwise silently removes existing files at directory/file collisions. */
    private fun validateFilePaths(paths: Set<String>) {
        paths.forEach { path ->
            var separator = path.indexOf('/')
            while (separator >= 0) {
                check(path.substring(0, separator) !in paths) { "SandboxPathConflict" }
                separator = path.indexOf('/', separator + 1)
            }
        }
    }

    private suspend fun textWithEnvironment(source: String, env: Map<String, String>, vararg args: String): String =
        call(source, *args, environment = env).text.trim()

    private companion object {
        const val TIMEOUT = 60_000L
        const val POLL = 50L
        const val MAX_OUTPUT = 16 * 1024 * 1024L
        val identity = mapOf(
            "GIT_AUTHOR_NAME" to "Sandbox",
            "GIT_AUTHOR_EMAIL" to "sandbox@localhost",
            "GIT_COMMITTER_NAME" to "Sandbox",
            "GIT_COMMITTER_EMAIL" to "sandbox@localhost",
        )
    }
}

internal data class GitBytes(val code: Int, val bytes: ByteArray) {
    val text: String get() = bytes.decodeToString()
    override fun toString(): String = "GitBytes(code=$code)"
}

internal data class GitEntry(val mode: String, val type: String, val objectId: String)
internal data class GitMerge(val tree: String, val conflicts: List<String>)

internal interface VirtualGitObjects {
    suspend fun call(
        source: String,
        vararg arguments: String,
        input: ByteArray = byteArrayOf(),
        environment: Map<String, String> = emptyMap(),
        allowFailure: Boolean = false,
    ): GitBytes
    suspend fun text(source: String, vararg arguments: String): String
    suspend fun entries(source: String, commit: String): Map<String, GitEntry>
    suspend fun scanTexts(source: String, entries: Map<String, GitEntry>, visit: (String, String) -> Boolean)
    suspend fun edit(source: String, commit: String, edits: Map<String, String?>): String
    suspend fun move(source: String, commit: String, path: String, destination: String): String
    suspend fun commit(source: String, tree: String, parent: String): String
    suspend fun merge(source: String, base: String, ours: String, theirs: String): GitMerge
    suspend fun pin(source: String, id: String, commit: String)
}
