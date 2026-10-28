package io.aequicor.heartbeat.feature.githubconnector.impl.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubConnectionUi
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubConnectorScreenIntent
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubConnectorScreenState
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubDialogUi
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubFailureUi
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubRepositoryUi
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubStepUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GitHubConnectorUiTest {
    @Test
    fun `an empty connector offers one connect button and a connected one offers change and disconnect`() =
        runSkikoComposeUiTest(size = Size(900f, 800f)) {
            val state = mutableStateOf(GitHubConnectorScreenState(isLoading = false))
            val intents = mutableListOf<GitHubConnectorScreenIntent>()
            setContent { HbTheme { GitHubConnectorContent(state.value, intents::add, null) } }
            onNodeWithTag("github-empty").assertIsDisplayed()
            onNodeWithTag("github-connect").performClick()
            assertEquals(GitHubConnectorScreenIntent.Connect, intents.last())

            runOnIdle { state.value = GitHubConnectorScreenState(false, GitHubConnectionUi("me", "me/repo")) }
            onNodeWithTag("github-repository").assertIsDisplayed()
            onNodeWithTag("github-change").performClick()
            assertEquals(GitHubConnectorScreenIntent.Connect, intents.last())
            onNodeWithTag("github-disconnect").performClick()
            assertEquals(GitHubConnectorScreenIntent.Disconnect, intents.last())
        }

    @Test
    fun `a failure explains itself and offers a retry`() = runSkikoComposeUiTest(size = Size(900f, 800f)) {
        val intents = mutableListOf<GitHubConnectorScreenIntent>()
        val state = GitHubConnectorScreenState(isLoading = false, failure = GitHubFailureUi.CliNotFound)
        setContent { HbTheme { GitHubConnectorContent(state, intents::add, null) } }
        onNodeWithTag("github-failure").assertIsDisplayed()
        onNodeWithTag("github-retry").performClick()
        assertEquals(GitHubConnectorScreenIntent.Retry, intents.last())
    }

    @Test
    fun `the dialog confirms only a chosen repository and sends choices`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            val repositories = persistentListOf(GitHubRepositoryUi("me/repo", false), GitHubRepositoryUi("me/p", true))
            val dialog = GitHubDialogUi(
                step = GitHubStepUi.ChoosingRepository,
                accounts = persistentListOf("me", "work"),
                account = "me",
                repositories = repositories,
            )
            val state = mutableStateOf(GitHubConnectorScreenState(isLoading = false, dialog = dialog))
            val intents = mutableListOf<GitHubConnectorScreenIntent>()
            setContent { HbTheme { GitHubConnectorContent(state.value, intents::add, null) } }
            onNodeWithTag("github-confirm").assertIsNotEnabled()
            onNodeWithTag("github-account-work").performClick()
            assertEquals(GitHubConnectorScreenIntent.SelectAccount("work"), intents.last())
            onNodeWithTag("github-repo-me/repo").performClick()
            assertEquals(GitHubConnectorScreenIntent.SelectRepository("me/repo"), intents.last())

            runOnIdle {
                val chosen = dialog.copy(repository = "me/repo")
                state.value = GitHubConnectorScreenState(isLoading = false, dialog = chosen)
            }
            onNodeWithTag("github-confirm").performClick()
            assertEquals(GitHubConnectorScreenIntent.Confirm, intents.last())
            onNodeWithTag("github-cancel").performClick()
            assertEquals(GitHubConnectorScreenIntent.Cancel, intents.last())
        }
}
