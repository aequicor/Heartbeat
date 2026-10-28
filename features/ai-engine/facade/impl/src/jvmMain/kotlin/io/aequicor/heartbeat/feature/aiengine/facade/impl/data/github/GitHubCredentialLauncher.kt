package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectionSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal interface GitHubCredentialChild : AutoCloseable {
    val output: InputStream
    val isAlive: Boolean
    val exitCode: Int
    fun closeInput()
}

internal fun interface GitHubCredentialLauncher {
    suspend fun start(account: String, workspace: WorkspaceRef): GitHubCredentialChild
}

/**
 * No PATH/CWD search. Only an absolute operator-configured executable outside known projects, the current
 * workspace and the parent's working directory may run. Project roots and executable links are canonicalized.
 * This trusts the operator's installed binary, not arbitrary project programs or model-supplied paths.
 */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopGitHubCredentialLauncher(
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
    private val connections: GitHubConnectionSource,
) : GitHubCredentialLauncher {
    override suspend fun start(account: String, workspace: WorkspaceRef): GitHubCredentialChild =
        withContext(dispatchers.io) {
            val configured = connections.current()?.cli ?: System.getenv(
                "HEARTBEAT_GITHUB_CLI",
            ) ?: error("GitHub credential executable not configured")
            val roots = workspaces.observe().first().mapNotNull { workspaces.resolve(it.ref) }.map(Path::of)
            val current = workspaces.resolve(workspace)
            check(current != null || workspaces.virtual(workspace) != null) { "Workspace unavailable" }
            val excluded = roots + listOfNotNull(current?.let(Path::of), Path.of(System.getProperty("user.dir")))
            val executable = resolveCredentialExecutable(configured, excluded)
            val child = ProcessBuilder(credentialCommand(executable, account))
                .directory(Path.of(System.getProperty("java.io.tmpdir")).toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { sanitizeCredentialEnvironment(environment()) }
                .start()
            ProcessCredentialChild(child)
        }
}

internal fun resolveCredentialExecutable(configured: String, projectRoots: List<Path>): Path {
    val requested = Path.of(configured)
    require(requested.isAbsolute) { "GitHub credential executable must be an absolute host path" }
    val executable = requested.toRealPath()
    require(
        Files.isRegularFile(executable) && Files.isExecutable(executable),
    ) { "GitHub credential executable unavailable" }
    require(
        projectRoots.none { executable.startsWith(it.toRealPath()) },
    ) { "Project credential executables are forbidden" }
    require(executable.fileName.toString() in setOf("gh", "gh.exe")) { "Only the configured GitHub CLI is supported" }
    return executable
}

internal fun credentialCommand(executable: Path, account: String): List<String> {
    require(executable.isAbsolute)
    require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(account))
    return listOf(executable.toString(), "auth", "token", "--hostname", "github.com", "--user", account)
}

private class ProcessCredentialChild(private val child: Process) : GitHubCredentialChild {
    override val output: InputStream get() = child.inputStream
    override val isAlive: Boolean get() = child.isAlive
    override val exitCode: Int get() = child.exitValue()
    override fun closeInput() = child.outputStream.close()

    override fun close() {
        try {
            child.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
            if (child.isAlive) child.destroyForcibly()
            check(child.waitFor(CLEANUP_SECONDS, TimeUnit.SECONDS)) { "Credential process cleanup unconfirmed" }
        } finally {
            child.inputStream.close()
            child.errorStream.close()
            child.outputStream.close()
        }
    }
}

private const val CLEANUP_SECONDS = 1L
