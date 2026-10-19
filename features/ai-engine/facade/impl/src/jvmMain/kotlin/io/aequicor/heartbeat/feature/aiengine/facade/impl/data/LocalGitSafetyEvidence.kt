package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Local Git facts do not depend on a remote or transport credentials. Reads only fixed metadata and active hook
 * files, never runs hooks, and binds the index, refs and hook contents to the existing one-shot authorization.
 * Hook text is untrusted evidence; ordinary project hooks are not by themselves a reason to refuse Git work.
 */
internal suspend fun captureLocalGitEvidence(
    directory: Path,
    executable: Path,
    environmentDigest: String,
    environment: Map<String, String>,
    git: suspend (List<String>) -> String,
): JsonObject {
    val root = Path.of(git(listOf("rev-parse", "--show-toplevel"))).toRealPath()
    require(!executable.startsWith(root))
    val gitDirectory = Path.of(git(listOf("rev-parse", "--absolute-git-dir"))).toRealPath()
    val commonDirectory = Path.of(git(listOf("rev-parse", "--path-format=absolute", "--git-common-dir"))).toRealPath()
    val allowedRoots = listOf(root, gitDirectory, commonDirectory)
    val branch = git(listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
    val head = git(listOf("rev-parse", "--verify", "--quiet", "HEAD"))
    val refs = git(listOf("for-each-ref", "--format=%(refname) %(objectname)"))
    val config = git(
        listOf(
            "config",
            "--null",
            "--get-regexp",
            "^(core\\.(hookspath|fsmonitor|editor|pager|attributesfile)|commit\\.gpgsign|gpg\\..*|" +
                "filter\\..*|merge\\..*|diff\\..*|alias\\..*|sequence\\.editor|pager\\..*|interactive\\.difffilter)$",
        ),
    )
    val executionEnvironment = environment.filterKeys {
        it.uppercase() in setOf(
            "EDITOR",
            "VISUAL",
            "PAGER",
            "GIT_EDITOR",
            "GIT_SEQUENCE_EDITOR",
            "GIT_PAGER",
        )
    }
    require(executionEnvironment.values.none(::hasCredentialText)) { "Sensitive Git execution environment" }
    require(!hasCredentialText(config)) { "Sensitive Git execution configuration" }
    val metadata = listOf("index", "MERGE_HEAD", "MERGE_MSG", "CHERRY_PICK_HEAD", "REVERT_HEAD").associateWith { name ->
        val path = Path.of(git(listOf("rev-parse", "--path-format=absolute", "--git-path", name)))
        localGitFile(path, allowedRoots, includeText = false)
    }
    val hooks = localGitHooks(allowedRoots, git)
    return buildJsonObject {
        put("kind", "localGit")
        put("status", "captured")
        put("root", root.toString())
        put("workingDirectory", directory.toString())
        put("gitExecutable", executable.toString())
        put("executionEnvironmentDigest", environmentDigest)
        put("currentBranch", branch)
        put("head", head)
        put("refsDigest", refs.toByteArray().toByteString().sha256().hex())
        put("executionConfiguration", config)
        put(
            "executionEnvironment",
            buildJsonObject {
                for ((name, value) in executionEnvironment.toSortedMap()) put(name, value)
            },
        )
        put("metadata", JsonObject(metadata))
        put("hooks", hooks)
        put("scope", "Local Git only; no origin, transport or network authorization is implied")
    }
}

private suspend fun localGitHooks(allowedRoots: List<Path>, git: suspend (List<String>) -> String): JsonArray {
    var hookBytes = 0L
    val hooks = LOCAL_HOOKS.map { name ->
        currentCoroutineContext().ensureActive()
        val path = Path.of(git(listOf("rev-parse", "--path-format=absolute", "--git-path", "hooks/$name")))
        val isActive = Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(path)
        val evidence = if (isActive) {
            localGitFile(path, allowedRoots, includeText = true)
        } else {
            buildJsonObject { put("exists", false) }
        }
        hookBytes += evidence.toString().toByteArray().size
        require(hookBytes <= MAX_HOOK_BYTES) { "Hook evidence exceeds budget" }
        buildJsonObject {
            put("name", name)
            put("path", path.toString())
            put("active", isActive)
            put("file", evidence)
        }
    }
    return JsonArray(hooks)
}

private suspend fun localGitFile(path: Path, allowedRoots: List<Path>, includeText: Boolean): JsonObject {
    currentCoroutineContext().ensureActive()
    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return buildJsonObject { put("exists", false) }
    require(allowedRoots.any { path.toRealPath().startsWith(it) }) { "Git evidence outside repository roots" }
    val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val limit = if (includeText) MAX_HOOK_BYTES else MAX_INDEX_BYTES
    require(before.isRegularFile && before.size() <= limit)
    val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
    require(bytes.size <= limit)
    val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    check(
        before.fileKey() == after.fileKey() && before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime(),
    )
    return buildJsonObject {
        put("exists", true)
        put("sha256", bytes.toByteString().sha256().hex())
        if (includeText) {
            val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            require(!content.contains('\u0000') && !hasCredentialText(content)) { "Sensitive or binary hook" }
            put("content", content)
        }
    }
}

private val LOCAL_HOOKS = listOf(
    "pre-commit", "prepare-commit-msg", "commit-msg", "post-commit", "pre-merge-commit", "post-merge",
    "post-checkout", "post-rewrite", "pre-rebase", "post-index-change", "reference-transaction",
    "applypatch-msg", "pre-applypatch", "post-applypatch",
)
private const val MAX_HOOK_BYTES = 32_768
private const val MAX_INDEX_BYTES = 16 * 1024 * 1024
