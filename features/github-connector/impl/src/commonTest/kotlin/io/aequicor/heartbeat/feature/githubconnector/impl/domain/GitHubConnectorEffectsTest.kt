package io.aequicor.heartbeat.feature.githubconnector.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnection
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorEffect
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorIntent
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubRepository
import io.aequicor.heartbeat.feature.githubconnector.impl.data.GitHubConnectorEffects
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GitHubConnectorEffectsTest {
    private val repo = GitHubRepository("me/repo", false)

    @Test
    fun `discover reports the cli and its accounts or its absence`() = runTest {
        val scope = Recording()
        effects(FakeCli(path = "/opt/homebrew/bin/gh", accounts = listOf("me"))).handle(
            GitHubConnectorEffect.Discover(null),
            scope,
        )
        effects(FakeCli(path = null)).handle(GitHubConnectorEffect.Discover(null), scope)
        assertEquals(
            listOf<GitHubConnectorIntent>(
                GitHubConnectorIntent.Internal.Discovered("/opt/homebrew/bin/gh", listOf("me")),
                GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.CliNotFound),
            ),
            scope.sent,
        )
    }

    @Test
    fun `verify saves the connection only when access is granted`() = runTest {
        val store = MemoryStore()
        val scope = Recording()
        val granted = FakeCli(access = GitHubAccess.Granted)
        effects(granted, store).handle(GitHubConnectorEffect.Verify("/gh", "me", "me/repo"), scope)
        val saved = GitHubConnection("me", "me/repo", "/gh")
        assertEquals(saved, store.saved)
        assertEquals(listOf<GitHubConnectorIntent>(GitHubConnectorIntent.Internal.Verified(saved)), scope.sent)

        val denied = MemoryStore()
        val other = Recording()
        effects(FakeCli(access = GitHubAccess.NoPullRequestAccess), denied)
            .handle(GitHubConnectorEffect.Verify("/gh", "me", "me/repo"), other)
        assertNull(denied.saved)
        assertEquals(
            listOf<GitHubConnectorIntent>(
                GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.NoPullRequestAccess),
            ),
            other.sent,
        )
    }

    @Test
    fun `typed and unexpected failures become step failures`() = runTest {
        val scope = Recording()
        effects(FakeCli(failure = GitHubCliException(GitHubConnectorFailure.NoAccess)))
            .handle(GitHubConnectorEffect.LoadRepositories("/gh", "me"), scope)
        effects(FakeCli(failure = IllegalStateException("boom")))
            .handle(GitHubConnectorEffect.LoadRepositories("/gh", "me"), scope)
        assertEquals(
            listOf<GitHubConnectorIntent>(
                GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.NoAccess),
                GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.Failed),
            ),
            scope.sent,
        )
    }

    @Test
    fun `restore and forget use the store`() = runTest {
        val saved = GitHubConnection("me", "me/repo", "/gh")
        val store = MemoryStore(saved)
        val scope = Recording()
        effects(FakeCli(), store).handle(GitHubConnectorEffect.Restore, scope)
        effects(FakeCli(), store).handle(GitHubConnectorEffect.Forget, scope)
        assertEquals(listOf<GitHubConnectorIntent>(GitHubConnectorIntent.Internal.Restored(saved)), scope.sent)
        assertNull(store.saved)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            effects(FakeCli(failure = kotlinx.coroutines.CancellationException("stop")))
                .handle(GitHubConnectorEffect.LoadRepositories("/gh", "me"), Recording())
        }
    }

    private fun effects(cli: GitHubCli, store: GitHubConnectionStore = MemoryStore()) =
        GitHubConnectorEffects(cli, store)

    private inner class FakeCli(
        private val path: String? = "/gh",
        private val accounts: List<String> = emptyList(),
        private val access: GitHubAccess = GitHubAccess.Granted,
        private val failure: Exception? = null,
    ) : GitHubCli {
        override suspend fun locate(preferred: String?): String? = path
        override suspend fun accounts(cli: String): List<String> = failure?.let { throw it } ?: accounts
        override suspend fun repositories(cli: String, account: String): List<GitHubRepository> =
            failure?.let { throw it } ?: listOf(repo)
        override suspend fun access(cli: String, account: String, repository: String): GitHubAccess = access
    }

    private class MemoryStore(var saved: GitHubConnection? = null) : GitHubConnectionStore {
        override suspend fun load(): GitHubConnection? = saved
        override suspend fun save(connection: GitHubConnection) {
            saved = connection
        }
        override suspend fun clear() {
            saved = null
        }
    }

    private class Recording : EffectScope<GitHubConnectorIntent> {
        val sent = mutableListOf<GitHubConnectorIntent>()
        override suspend fun send(intent: GitHubConnectorIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
