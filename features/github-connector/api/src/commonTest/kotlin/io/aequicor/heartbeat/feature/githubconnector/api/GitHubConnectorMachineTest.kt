package io.aequicor.heartbeat.feature.githubconnector.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test

class GitHubConnectorMachineTest {
    private val saved = GitHubConnection("me", "me/repo", "/opt/homebrew/bin/gh")
    private val repos = listOf(GitHubRepository("me/repo", false), GitHubRepository("me/other", true))

    @Test
    fun `start restores the saved connection`() {
        val spec = GitHubConnectorMachineSpec
        spec.assertTransition(
            GitHubConnectorState.Loading,
            GitHubConnectorIntent.Public.Start,
            GitHubConnectorState.Loading,
            effects = listOf(GitHubConnectorEffect.Restore),
        )
        spec.assertTransition(
            GitHubConnectorState.Loading,
            GitHubConnectorIntent.Internal.Restored(saved),
            GitHubConnectorState.Connected(saved),
        )
        spec.assertTransition(
            GitHubConnectorState.Loading,
            GitHubConnectorIntent.Internal.Restored(null),
            GitHubConnectorState.Disconnected(),
        )
    }

    @Test
    fun `connect discovers then loads repositories of the first account`() {
        val spec = GitHubConnectorMachineSpec
        val discovering = GitHubConnectorState.Connecting(GitHubConnectStep.Discovering)
        spec.assertTransition(
            GitHubConnectorState.Disconnected(),
            GitHubConnectorIntent.Public.Connect,
            discovering,
            effects = listOf(GitHubConnectorEffect.Discover(null)),
        )
        spec.assertTransition(
            discovering,
            GitHubConnectorIntent.Internal.Discovered("/gh", listOf("a", "b")),
            GitHubConnectorState.Connecting(
                GitHubConnectStep.LoadingRepositories,
                cli = "/gh",
                accounts = listOf("a", "b"),
                account = "a",
            ),
            effects = listOf(GitHubConnectorEffect.LoadRepositories("/gh", "a")),
        )
        spec.assertTransition(
            discovering,
            GitHubConnectorIntent.Internal.Discovered("/gh", emptyList()),
            GitHubConnectorState.Disconnected(GitHubConnectorFailure.NotLoggedIn),
        )
    }

    @Test
    fun `changing a saved connection prefers its account and cancel restores it`() {
        val spec = GitHubConnectorMachineSpec
        val discovering = GitHubConnectorState.Connecting(GitHubConnectStep.Discovering, previous = saved)
        spec.assertTransition(
            GitHubConnectorState.Connected(saved),
            GitHubConnectorIntent.Public.Connect,
            discovering,
            effects = listOf(GitHubConnectorEffect.Discover(saved.cli)),
        )
        spec.assertTransition(
            discovering,
            GitHubConnectorIntent.Internal.Discovered("/gh", listOf("a", "me")),
            discovering.copy(
                step = GitHubConnectStep.LoadingRepositories,
                cli = "/gh",
                accounts = listOf("a", "me"),
                account = "me",
            ),
            effects = listOf(GitHubConnectorEffect.LoadRepositories("/gh", "me")),
        )
        spec.assertTransition(discovering, GitHubConnectorIntent.Public.Cancel, GitHubConnectorState.Connected(saved))
    }

    @Test
    fun `repositories are chosen from the loaded list and verified once`() {
        val spec = GitHubConnectorMachineSpec
        val loading = GitHubConnectorState.Connecting(
            GitHubConnectStep.LoadingRepositories,
            cli = "/gh",
            accounts = listOf("me"),
            account = "me",
        )
        val choosing = loading.copy(step = GitHubConnectStep.ChoosingRepository, repositories = repos)
        spec.assertTransition(loading, GitHubConnectorIntent.Internal.RepositoriesLoaded("me", repos), choosing)
        spec.assertIgnored(loading, GitHubConnectorIntent.Internal.RepositoriesLoaded("stale", repos))
        spec.assertIgnored(choosing, GitHubConnectorIntent.Public.Confirm)
        spec.assertIgnored(choosing, GitHubConnectorIntent.Public.SelectRepository("unknown/repo"))
        val chosen = choosing.copy(repository = "me/repo")
        spec.assertTransition(choosing, GitHubConnectorIntent.Public.SelectRepository("me/repo"), chosen)
        val verifying = chosen.copy(step = GitHubConnectStep.Verifying)
        spec.assertTransition(
            chosen,
            GitHubConnectorIntent.Public.Confirm,
            verifying,
            effects = listOf(GitHubConnectorEffect.Verify("/gh", "me", "me/repo")),
        )
        spec.assertIgnored(verifying, GitHubConnectorIntent.Public.Confirm)
        spec.assertTransition(
            verifying,
            GitHubConnectorIntent.Internal.Verified(saved),
            GitHubConnectorState.Connected(saved),
            effects = listOf(GitHubConnectorEffect.Save(saved)),
        )
        // A result of a cancelled attempt finds no matching step and is never saved.
        spec.assertIgnored(GitHubConnectorState.Disconnected(), GitHubConnectorIntent.Internal.Verified(saved))
        spec.assertIgnored(GitHubConnectorState.Connected(saved), GitHubConnectorIntent.Internal.Verified(saved))
        spec.assertIgnored(chosen, GitHubConnectorIntent.Internal.Verified(saved))
    }

    @Test
    fun `failures return to the step that can fix them`() {
        val spec = GitHubConnectorMachineSpec
        val failure = GitHubConnectorFailure.NoPullRequestAccess
        val verifying = GitHubConnectorState.Connecting(
            GitHubConnectStep.Verifying,
            cli = "/gh",
            accounts = listOf("me"),
            account = "me",
            repositories = repos,
            repository = "me/repo",
        )
        spec.assertTransition(
            verifying,
            GitHubConnectorIntent.Internal.StepFailed(failure),
            verifying.copy(step = GitHubConnectStep.ChoosingRepository, failure = failure),
        )
        val loading = verifying.copy(step = GitHubConnectStep.LoadingRepositories, repositories = emptyList())
        spec.assertTransition(
            loading,
            GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.Failed),
            loading.copy(step = GitHubConnectStep.ChoosingAccount, failure = GitHubConnectorFailure.Failed),
        )
        val discovering = GitHubConnectorState.Connecting(GitHubConnectStep.Discovering)
        spec.assertTransition(
            discovering,
            GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.CliNotFound),
            GitHubConnectorState.Disconnected(GitHubConnectorFailure.CliNotFound),
        )
        spec.assertTransition(
            GitHubConnectorState.Disconnected(GitHubConnectorFailure.CliNotFound),
            GitHubConnectorIntent.Public.Retry,
            discovering,
            effects = listOf(GitHubConnectorEffect.Discover(null)),
        )
        spec.assertIgnored(GitHubConnectorState.Disconnected(), GitHubConnectorIntent.Public.Retry)
    }

    @Test
    fun `disconnect waits for the deletion and a failure keeps the connection`() {
        val spec = GitHubConnectorMachineSpec
        spec.assertTransition(
            GitHubConnectorState.Connected(saved, GitHubConnectorFailure.Failed),
            GitHubConnectorIntent.Public.Disconnect,
            GitHubConnectorState.Connected(saved),
            effects = listOf(GitHubConnectorEffect.Forget),
        )
        spec.assertTransition(
            GitHubConnectorState.Connected(saved),
            GitHubConnectorIntent.Internal.Forgotten,
            GitHubConnectorState.Disconnected(),
        )
        spec.assertTransition(
            GitHubConnectorState.Connected(saved),
            GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.Failed),
            GitHubConnectorState.Connected(saved, GitHubConnectorFailure.Failed),
        )
    }

    @Test
    fun `a failed restore is shown and every later transition keeps the previous connection`() {
        val spec = GitHubConnectorMachineSpec
        spec.assertTransition(
            GitHubConnectorState.Loading,
            GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.Failed),
            GitHubConnectorState.Disconnected(GitHubConnectorFailure.Failed),
        )
        val loading = GitHubConnectorState.Connecting(
            GitHubConnectStep.LoadingRepositories,
            previous = saved,
            cli = "/gh",
            accounts = listOf("me"),
            account = "me",
        )
        spec.assertTransition(
            loading,
            GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.NoAccess),
            loading.copy(step = GitHubConnectStep.ChoosingAccount, failure = GitHubConnectorFailure.NoAccess),
        )
        spec.assertTransition(loading, GitHubConnectorIntent.Public.Cancel, GitHubConnectorState.Connected(saved))
        spec.assertIgnored(loading, GitHubConnectorIntent.Public.SelectAccount("me"))
        spec.assertIgnored(loading, GitHubConnectorIntent.Public.Disconnect)
        spec.assertIgnored(loading, GitHubConnectorIntent.Public.Connect)
    }
}
