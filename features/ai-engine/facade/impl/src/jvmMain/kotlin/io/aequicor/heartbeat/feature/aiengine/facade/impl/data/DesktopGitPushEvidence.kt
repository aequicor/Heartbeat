package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Local, bounded evidence for the three simple push forms. Never pushes, contacts a remote, reads credentials or
 * runs hooks. Unknown environment overrides and secret-bearing URLs produce unavailable evidence, never a grant.
 * Facts are stable across capture and consumption; the independent reviewer still decides whether they are safe.
 */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class DesktopGitPushEvidence(private val dispatchers: DispatcherProvider) : CommandSafetyEvidence {
    private val log = Log.tag("DesktopGitPushEvidence")

    override suspend fun capture(directory: String, command: String, environment: Map<String, String>?): JsonObject? {
        val form = PUSH.matchEntire(command.trim()) ?: return null
        val capturedEnvironment = (environment ?: System.getenv()).toMap()
        return try {
            withContext(dispatchers.io) {
                withTimeoutOrNull(CAPTURE_MILLIS) {
                    inspect(Path.of(directory).toRealPath(), form, capturedEnvironment)
                } ?: unavailable("capture timeout")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Git push evidence unavailable" }
            unavailable("local git evidence unavailable")
        }
    }

    private suspend fun inspect(directory: Path, form: MatchResult, environment: Map<String, String>): JsonObject {
        val hasOverrides = environment.keys.any(::isOverride)
        if (hasOverrides) return unavailable("environment overrides", true)
        val executable = gitExecutable(directory, environment)
        val root = Path.of(git(executable, directory, environment, "rev-parse", "--show-toplevel")).toRealPath()
        require(!executable.startsWith(root))
        val branch = git(executable, directory, environment, "symbolic-ref", "--quiet", "--short", "HEAD")
        require(SAFE_VALUE.matches(branch))
        val head = git(executable, directory, environment, "rev-parse", "--verify", "HEAD")
        require(Regex("[a-fA-F0-9]{40,64}").matches(head))
        val urls = git(executable, directory, environment, "remote", "get-url", "--push", "--all", "origin").lines()
        if (urls.isEmpty() || urls.any { !isPublicUrl(it) }) return unavailable("origin URL is private or unsupported")
        val config = configuration(executable, directory, environment, branch)
        val credentials = credentialHelpers(executable, directory, environment, root)
        val hasCustomTransport = git(
            executable,
            directory,
            environment,
            "config",
            "--name-only",
            "--get-regexp",
            "^(core\\.(sshcommand|gitproxy|askpass|fsmonitor|alternaterefscommand)|" +
                "extensions\\.partialclone|ssh\\.variant|remote\\.origin\\.(receivepack|vcs|proxy)|" +
                "http\\..*|https\\..*|protocol\\..*|push\\.pushoption)$",
            allowMissing = true,
        ).isNotBlank()
        val hook = Path.of(
            git(
                executable,
                directory,
                environment,
                "rev-parse",
                "--path-format=absolute",
                "--git-path",
                "hooks/pre-push",
            ),
        )
        return buildJsonObject {
            put("kind", "gitPush")
            put("status", "captured")
            put("root", root.toString())
            put("gitExecutable", executable.toString())
            put("executionEnvironmentDigest", environmentDigest(environment))
            put("currentBranch", branch)
            put("head", head)
            put("requestedBranch", form.groups[1]?.value)
            put("usesDefaultDestination", form.groups[1] == null)
            put("originPushUrls", JsonArray(urls.map(::JsonPrimitive)))
            put("standardCredentialHelpers", JsonArray(credentials.standardHelpers.map(::JsonPrimitive)))
            put(
                "standardCredentialHelperExecutables",
                JsonObject(credentials.executables.mapValues { JsonPrimitive(it.value) }),
            )
            put("config", JsonObject(config.toSortedMap().mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
            put("prePushHookPresent", !Files.notExists(hook, LinkOption.NOFOLLOW_LINKS))
            put("customTransportOrPushOptions", hasCustomTransport || credentials.hasCustomHelper)
            put("environmentOverrides", false)
        }
    }

    private suspend fun credentialHelpers(
        executable: Path,
        directory: Path,
        environment: Map<String, String>,
        root: Path,
    ): CredentialHelperEvidence {
        val records = git(
            executable,
            directory,
            environment,
            "config",
            "--null",
            "--get-regexp",
            "^credential\\.(.*\\.)?helper$",
            allowMissing = true,
        ).split('\u0000').filter { it.isNotEmpty() }
        val standard = mutableListOf<String>()
        var hasCustomHelper = false
        var hasScopedHelper = false
        for (record in records) {
            val parts = record.split('\n', limit = 2)
            require(parts.size == 2)
            when {
                parts[0] != "credential.helper" -> hasScopedHelper = true

                parts[1].isEmpty() -> {
                    standard.clear()
                    hasCustomHelper = false
                }

                parts[1] in STANDARD_CREDENTIAL_HELPERS -> standard += parts[1]

                else -> hasCustomHelper = true
            }
        }
        val executables = credentialExecutables(executable, directory, environment, root, standard)
        val hasUnresolvedHelper = executables.size != standard.distinct().size
        return CredentialHelperEvidence(
            standard,
            executables,
            hasCustomHelper || hasScopedHelper || hasUnresolvedHelper,
        )
    }

    private suspend fun credentialExecutables(
        executable: Path,
        directory: Path,
        environment: Map<String, String>,
        root: Path,
        helpers: List<String>,
    ): Map<String, String> {
        if (helpers.isEmpty()) return emptyMap()
        val execPath = Path.of(git(executable, directory, environment, "--exec-path"))
        val search = listOf(execPath) + environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }
            ?.value.orEmpty().split(File.pathSeparator).map(Path::of)
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val suffix = if (isWindows) ".exe" else ""
        val alternatives = if (isWindows) {
            environment.entries.firstOrNull { it.key.equals("PATHEXT", ignoreCase = true) }?.value.orEmpty()
                .split(';').map(String::lowercase).filter { it != ".exe" } + listOf(".ps1", "")
        } else {
            emptyList()
        }
        return helpers.asSequence().distinct().sorted().mapNotNull { helper ->
            val hasAmbiguousWrapper = (listOf(directory) + search).any { parent ->
                alternatives.any { Files.isRegularFile(parent.resolve("git-credential-$helper$it")) }
            }
            val name = "git-credential-$helper$suffix"
            val parent = search.firstOrNull {
                val candidate = (if (it.isAbsolute) it else directory.resolve(it)).resolve(name)
                Files.isRegularFile(candidate) && Files.isExecutable(candidate)
            }
            val resolved = parent?.takeIf { it.isAbsolute }?.resolve(name)?.toRealPath()
            if (hasAmbiguousWrapper || resolved == null || resolved.startsWith(root)) {
                null
            } else {
                helper to resolved.toString()
            }
        }.toMap()
    }

    private suspend fun configuration(
        executable: Path,
        directory: Path,
        environment: Map<String, String>,
        branch: String,
    ): Map<String, List<String>> {
        val branchKey = "branch\\." + branch.map {
            if (it in ".[]\\*+?(){}^$|") "\\$it" else "$it"
        }.joinToString("") + "\\."
        return git(
            executable,
            directory,
            environment,
            "config",
            "--null",
            "--get-regexp",
            "^(push\\.(default|followtags|recursesubmodules|gpgsign|autosetupremote)|" +
                "submodule\\.recurse|remote\\.(origin\\.(push|mirror)|pushdefault)|" +
                "$branchKey(remote|pushremote|merge))$",
            allowMissing = true,
        ).split('\u0000').filter { it.isNotEmpty() }.map { record ->
            val parts = record.split('\n', limit = 2)
            require(parts.size == 2 && isPublicConfig(parts[0], parts[1]))
            parts[0] to parts[1]
        }.groupBy({ it.first }, { it.second })
    }

    private fun gitExecutable(directory: Path, environment: Map<String, String>): Path {
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val name = if (isWindows) "git.exe" else "git"
        require(!Files.exists(directory.resolve(name)))
        val search = environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value
            .orEmpty().split(File.pathSeparator).map(Path::of)
        if (isWindows) {
            require(environment.keys.map(String::uppercase).distinct().size == environment.size)
            val extensions = environment.entries.firstOrNull { it.key.equals("PATHEXT", ignoreCase = true) }?.value
                .orEmpty().split(';').map(String::lowercase)
            require(extensions.all { Regex("\\.[a-z0-9]+").matches(it) })
            val alternatives = (extensions + ".ps1").filter { it != ".exe" }.map { "git$it" }
            require(
                (listOf(directory) + search).none { path ->
                    alternatives.any { Files.isRegularFile(path.resolve(it)) }
                },
            )
        }
        val parent = search.firstOrNull {
            val candidate = (if (it.isAbsolute) it else directory.resolve(it)).resolve(name)
            Files.isRegularFile(candidate) && Files.isExecutable(candidate)
        }
        require(parent != null && parent.isAbsolute)
        val candidate = parent.resolve(name)
        return candidate.toRealPath().also { require(!it.startsWith(directory)) }
    }

    private suspend fun git(
        executable: Path,
        directory: Path,
        capturedEnvironment: Map<String, String>,
        vararg arguments: String,
        allowMissing: Boolean = false,
    ): String {
        currentCoroutineContext().ensureActive()
        val child = ProcessBuilder(listOf(executable.toString(), "--no-pager") + arguments)
            .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment().clear()
                environment().putAll(capturedEnvironment)
                environment().keys.removeAll(::isGitOrSshName)
            }.start()
        val output = ByteArrayOutputStream()
        try {
            child.outputStream.close()
            val input = child.inputStream
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val available = input.available()
                if (available > 0) {
                    val count = input.read(buffer, 0, minOf(available, buffer.size))
                    if (count > 0) output.write(buffer, 0, count)
                    require(output.size() <= MAX_OUTPUT_BYTES)
                } else if (!child.isAlive) {
                    break
                } else {
                    delay(POLL_MILLIS)
                }
            }
            val code = child.exitValue()
            require(code == 0 || (allowMissing && code == 1))
            return output.toString(Charsets.UTF_8).trimEnd('\r', '\n')
        } finally {
            withContext(NonCancellable) {
                try {
                    child.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    if (child.isAlive) child.destroyForcibly()
                    check(child.waitFor(CLEANUP_MILLIS, TimeUnit.MILLISECONDS))
                } finally {
                    closeStreams(child)
                }
            }
        }
    }

    private fun closeStreams(child: Process) {
        for (stream in listOf(child.inputStream, child.errorStream, child.outputStream)) {
            try {
                stream.close()
            } catch (error: IOException) {
                log.w(error.redactedFailure()) { "Git evidence stream cleanup failed" }
            }
        }
    }
}

private data class CredentialHelperEvidence(
    val standardHelpers: List<String>,
    val executables: Map<String, String>,
    val hasCustomHelper: Boolean,
)

private fun unavailable(reason: String, hasEnvironmentOverrides: Boolean = false): JsonObject = buildJsonObject {
    put("kind", "gitPush")
    put("status", "unavailable")
    put("reason", reason)
    put("environmentOverrides", hasEnvironmentOverrides)
}

private fun isOverride(name: String): Boolean = name.uppercase().let { upper ->
    val isShellOverride = upper.startsWith("BASH_FUNC_") || upper in setOf("BASH_ENV", "ENV", "ZDOTDIR")
    val isTransportOverride = upper.startsWith("CURL_") || upper.startsWith("GCM_") || upper in TRANSPORT_ENVIRONMENT
    val isLoaderOverride = upper.startsWith("DYLD_") || upper in setOf("LD_PRELOAD", "LD_LIBRARY_PATH")
    val hasHazardousName = isGitOrSshName(upper) || isShellOverride || isTransportOverride || isLoaderOverride
    hasHazardousName && upper !in setOf("SSH_AUTH_SOCK", "SSH_AGENT_PID", "GIT_PAGER")
}

private fun isGitOrSshName(name: String): Boolean = name.uppercase().let {
    it.startsWith("GIT_") || it.startsWith("SSH_")
}

private fun environmentDigest(environment: Map<String, String>): String =
    JsonObject(environment.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString().encodeUtf8().sha256().hex()

private fun isPublicUrl(value: String): Boolean {
    val hasUnsafeCharacters = value.any { it.isWhitespace() || it in "?#%\\" }
    if (hasUnsafeCharacters) return false
    val uri = URI(value)
    return uri.scheme == "https" && uri.host != null && uri.userInfo == null
}

private fun isPublicConfig(key: String, value: String): Boolean {
    if (!SAFE_VALUE.matches(value)) return false
    return when {
        key.endsWith(".remote") || key.endsWith(".pushremote") || key == "remote.pushdefault" ->
            Regex("[A-Za-z0-9._/-]+").matches(value)

        key.endsWith(".merge") -> value.startsWith("refs/heads/")

        key == "remote.origin.push" ->
            value == ":" || Regex("\\+?refs/[A-Za-z0-9._/*-]+(?::refs/[A-Za-z0-9._/*-]+)?").matches(value)

        else -> Regex("[A-Za-z0-9-]*").matches(value)
    }
}

private val PUSH = Regex("git[ \\t]+push(?:[ \\t]+(?:-u[ \\t]+)?origin[ \\t]+([A-Za-z0-9][A-Za-z0-9._/-]*))?")
private val SAFE_VALUE = Regex("[A-Za-z0-9._/:+*^@-]*")
private val STANDARD_CREDENTIAL_HELPERS = setOf("osxkeychain", "manager", "manager-core", "cache")
private val TRANSPORT_ENVIRONMENT = setOf(
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "ALL_PROXY",
    "NO_PROXY",
    "SSL_CERT_FILE",
    "SSL_CERT_DIR",
)
private const val CAPTURE_MILLIS = 10_000L
private const val CLEANUP_MILLIS = 250L
private const val POLL_MILLIS = 10L
private const val BUFFER_BYTES = 4096
private const val MAX_OUTPUT_BYTES = 65_536
