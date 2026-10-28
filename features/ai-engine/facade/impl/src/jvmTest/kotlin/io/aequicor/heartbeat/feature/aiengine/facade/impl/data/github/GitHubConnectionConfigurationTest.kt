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
        val cli = Files.createTempFile("gh", "").toFile().apply { setExecutable(true) }
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
}
