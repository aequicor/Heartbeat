package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

/** Local credential material only; implementations must never log or expose subprocess diagnostics. */
internal fun interface GitHubPullCredentials {
    suspend fun read(account: String, workspace: WorkspaceRef): String
}

/** Bounded credential reader; the injected launcher pins a trusted executable and excludes project locations. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopGitHubPullCredentials(
    private val dispatchers: DispatcherProvider,
    private val launcher: GitHubCredentialLauncher,
) : GitHubPullCredentials {
    override suspend fun read(account: String, workspace: WorkspaceRef): String = withContext(dispatchers.io) {
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(account))
        val child = launcher.start(account, workspace)
        try {
            child.closeInput()
            withTimeout(CREDENTIAL_SECONDS.seconds) {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(CREDENTIAL_BYTES)
                while (child.isAlive) {
                    ensureActive()
                    drainCredential(child, output, buffer)
                    delay(CREDENTIAL_POLL_MILLIS)
                }
                drainCredential(child, output, buffer)
                check(child.exitCode == 0) { "Configured GitHub login is unavailable" }
                output.toString(Charsets.UTF_8).trim().also {
                    check(it.isNotEmpty()) { "Configured GitHub login is empty" }
                }
            }
        } finally {
            child.close()
        }
    }
}

/** Even inherited variables cannot change the selected account, host, diagnostics or prompting behavior. */
internal fun sanitizeCredentialEnvironment(environment: MutableMap<String, String>) {
    environment.keys.filter {
        it.uppercase() in setOf(
            "GH_TOKEN",
            "GITHUB_TOKEN",
            "GH_ENTERPRISE_TOKEN",
            "GITHUB_ENTERPRISE_TOKEN",
            "GH_DEBUG",
            "GH_HOST",
        )
    }.forEach(environment::remove)
    environment["GH_PROMPT_DISABLED"] = "1"
    environment["GH_PAGER"] = "cat"
    environment["NO_COLOR"] = "1"
}

private fun drainCredential(child: GitHubCredentialChild, output: ByteArrayOutputStream, buffer: ByteArray) {
    while (child.output.available() > 0) {
        val count = child.output.read(buffer)
        if (count <= 0) break
        check(output.size() + count <= CREDENTIAL_BYTES) { "Credential output exceeded the limit" }
        output.write(buffer, 0, count)
    }
}

private const val CREDENTIAL_BYTES = 8192
private const val CREDENTIAL_SECONDS = 5
private const val CREDENTIAL_POLL_MILLIS = 10L
