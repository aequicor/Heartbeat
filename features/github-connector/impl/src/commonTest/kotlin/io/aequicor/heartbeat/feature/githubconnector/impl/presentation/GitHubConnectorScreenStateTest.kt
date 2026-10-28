package io.aequicor.heartbeat.feature.githubconnector.impl.presentation

import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectStep
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnection
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorState
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubConnectorScreenStateTest {
    private val saved = GitHubConnection("me", "me/repo", "/opt/homebrew/bin/gh")

    @Test
    fun `loading and disconnected states carry no connection or dialog`() {
        val initial = GitHubConnectorScreenState().reflecting(GitHubConnectorState.Loading)
        assertTrue(initial.isLoading)
        val failed = initial.copy(query = "x").reflecting(
            GitHubConnectorState.Disconnected(GitHubConnectorFailure.CliNotFound),
        )
        assertEquals(false, failed.isLoading)
        assertNull(failed.connection)
        assertNull(failed.dialog)
        assertEquals(GitHubFailureUi.CliNotFound, failed.failure)
        assertEquals("", failed.query)
    }

    @Test
    fun `connected state shows the repository and never the cli path`() {
        val state = GitHubConnectorScreenState().reflecting(GitHubConnectorState.Connected(saved))
        assertEquals(GitHubConnectionUi("me", "me/repo"), state.connection)
        assertNull(state.failure)
    }

    @Test
    fun `the dialog keeps the previous connection and filters repositories by the local query`() {
        val state = GitHubConnectorScreenState(query = "oth").reflecting(
            GitHubConnectorState.Connecting(
                step = GitHubConnectStep.ChoosingRepository,
                previous = saved,
                cli = "/gh",
                accounts = listOf("me"),
                account = "me",
                repositories = listOf(GitHubRepository("me/repo", false), GitHubRepository("me/other", true)),
                repository = "me/repo",
            ),
        )
        val dialog = requireNotNull(state.dialog)
        assertEquals(GitHubStepUi.ChoosingRepository, dialog.step)
        assertTrue(dialog.canConfirm)
        assertEquals(GitHubConnectionUi("me", "me/repo"), state.connection)
        assertEquals(listOf(GitHubRepositoryUi("me/other", true)), state.visibleRepositories)
    }

    @Test
    fun `confirm is unavailable until a repository is chosen`() {
        val state = GitHubConnectorScreenState().reflecting(
            GitHubConnectorState.Connecting(GitHubConnectStep.ChoosingRepository, account = "me"),
        )
        assertEquals(false, requireNotNull(state.dialog).canConfirm)
    }
}
