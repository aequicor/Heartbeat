package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnection
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectionSource
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubConnectionConfigurationTest {
    private val workspace = WorkspaceRef("workspace")

    @Test
    fun `a saved connection configures the repository and the account of the tools`() = runTest {
        val dir = Files.createTempDirectory("gh-dir")
        val cli = dir.resolve("gh").toFile().apply {
            createNewFile()
            setExecutable(true)
        }
        try {
            val read = mutableListOf<String>()
            val source = DesktopGitHubPullConfiguration(
                { account, _ ->
                    read += account
                    "tok"
                },
                GitHubConnectionSource { GitHubConnection("me", "me/repo", cli.path) },
            )
            assertTrue(source.isAvailable())
            val configuration = requireNotNull(source.current(workspace))
            assertEquals("me/repo", configuration.repository)
            assertEquals("me", configuration.account)
            assertEquals(listOf("me"), read)
        } finally {
            cli.delete()
            dir.toFile().delete()
        }
    }

    @Test
    fun `a saved connection with a missing cli is not offered`() = runTest {
        val source = DesktopGitHubPullConfiguration(
            { _, _ -> "tok" },
            GitHubConnectionSource { GitHubConnection("me", "me/repo", "/does/not/exist/gh") },
        )
        assertFalse(source.isAvailable())
    }

    @Test
    fun `a connection with an unusable executable name account or repository is not offered`() = runTest {
        val dir = Files.createTempDirectory("tool-dir")
        val other = dir.resolve("not-gh").toFile().apply {
            createNewFile()
            setExecutable(true)
        }
        val gh = dir.resolve("gh").toFile().apply {
            createNewFile()
            setExecutable(true)
        }
        try {
            suspend fun available(connection: GitHubConnection) =
                DesktopGitHubPullConfiguration({ _, _ -> "tok" }, GitHubConnectionSource { connection }).isAvailable()
            assertFalse(available(GitHubConnection("me", "me/r", other.path)))
            assertFalse(available(GitHubConnection("bad name", "me/r", gh.path)))
            assertFalse(available(GitHubConnection("me", "--flag", gh.path)))
            assertTrue(available(GitHubConnection("me", "me/r", gh.path)))
        } finally {
            other.delete()
            gh.delete()
            dir.toFile().delete()
        }
    }

    @Test
    fun `without a connection the host needs its environment configuration`() = runTest {
        val source = DesktopGitHubPullConfiguration({ _, _ -> "tok" }, GitHubConnectionSource { null })
        // The test environment defines none of the HEARTBEAT_GITHUB_ variables.
        assertFalse(source.isAvailable())
        assertEquals(null, source.current(workspace))
    }
}
