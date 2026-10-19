package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubPullCredentialsTest {
    @Test
    fun `credential launcher cannot select a project executable or a bare name`() {
        val root = Files.createTempDirectory("credential-launch")
        try {
            val project = Files.createDirectory(root.resolve("checkout"))
            val installed = Files.createDirectory(root.resolve("installed"))
            val malicious = Files.writeString(project.resolve("gh.exe"), "not executed")
            val trusted = Files.writeString(installed.resolve("gh.exe"), "not executed")
            malicious.toFile().setExecutable(true)
            trusted.toFile().setExecutable(true)
            assertFailsWith<IllegalArgumentException> { resolveCredentialExecutable("gh", listOf(project)) }
            assertFailsWith<IllegalArgumentException> {
                resolveCredentialExecutable(malicious.toString(), listOf(project))
            }
            val selected = resolveCredentialExecutable(trusted.toString(), listOf(project))
            val command = credentialCommand(selected, "selected-user")
            assertEquals(trusted.toRealPath().toString(), command.first())
            assertEquals(
                listOf("auth", "token", "--hostname", "github.com", "--user", "selected-user"),
                command.drop(1),
            )
            assertTrue(Path.of(command.first()).isAbsolute)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `inherited credential host and diagnostic overrides cannot override the selected source`() {
        val environment = mutableMapOf(
            "GH_TOKEN" to "other-account-token",
            "github_token" to "other-token",
            "GH_ENTERPRISE_TOKEN" to "enterprise-token",
            "GITHUB_ENTERPRISE_TOKEN" to "other-enterprise-token",
            "GH_DEBUG" to "api",
            "GH_HOST" to "other.invalid",
            "PATH" to "system-cli-directory",
        )
        sanitizeCredentialEnvironment(environment)
        assertFalse(environment.keys.any { "TOKEN" in it.uppercase() })
        assertFalse("GH_DEBUG" in environment)
        assertFalse("GH_HOST" in environment)
        assertEquals("1", environment["GH_PROMPT_DISABLED"])
        assertEquals("system-cli-directory", environment["PATH"])
    }

    @Test
    fun `credential success closes child and returns only private stdout`() = runTest {
        val child = FakeCredentialChild("fixture-token\n")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = DesktopGitHubPullCredentials(dispatcher.provider(), GitHubCredentialLauncher { _, _ -> child })
        assertEquals("fixture-token", reader.read("account", WorkspaceRef("project")))
        assertTrue(child.closed)
        assertTrue(child.inputClosed)
    }

    @Test
    fun `credential timeout and cancellation close the child`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val timedOut = FakeCredentialChild("", alive = true)
        val timeoutReader = DesktopGitHubPullCredentials(
            dispatcher.provider(),
            GitHubCredentialLauncher { _, _ -> timedOut },
        )
        assertFailsWith<TimeoutCancellationException> { timeoutReader.read("account", WorkspaceRef("project")) }
        assertTrue(timedOut.closed)

        val cancelled = FakeCredentialChild("", alive = true)
        val cancelReader = DesktopGitHubPullCredentials(
            dispatcher.provider(),
            GitHubCredentialLauncher { _, _ -> cancelled },
        )
        val job = async { cancelReader.read("account", WorkspaceRef("project")) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled.closed)
    }

    @Test
    fun `overflow nonzero exit and empty output fail with no credential in diagnostics`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (child in listOf(
            FakeCredentialChild("SENSITIVE".repeat(2048)),
            FakeCredentialChild("SENSITIVE", status = 1),
            FakeCredentialChild(""),
        )) {
            val reader = DesktopGitHubPullCredentials(dispatcher.provider(), GitHubCredentialLauncher { _, _ -> child })
            val error = assertFailsWith<IllegalStateException> { reader.read("account", WorkspaceRef("project")) }
            assertFalse(error.message.orEmpty().contains("SENSITIVE"))
            assertTrue(child.closed)
        }
    }
}

private class FakeCredentialChild(text: String, alive: Boolean = false, private val status: Int = 0) :
    GitHubCredentialChild {
    override val output = ByteArrayInputStream(text.encodeToByteArray())
    override var isAlive = alive
    override val exitCode: Int get() = status
    var closed = false
    var inputClosed = false
    override fun closeInput() {
        inputClosed = true
    }

    override fun close() {
        closed = true
        isAlive = false
        output.close()
    }
}

private fun kotlinx.coroutines.CoroutineDispatcher.provider() = object : DispatcherProvider {
    override val main = this@provider
    override val default = this@provider
    override val io = this@provider
}
