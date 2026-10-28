package io.aequicor.heartbeat.feature.githubconnector.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorIntent
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorMachineSpec
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorOutput
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorState
import io.aequicor.heartbeat.feature.githubconnector.impl.data.CliHost
import io.aequicor.heartbeat.feature.githubconnector.impl.data.GitHubConnectorEffects
import io.aequicor.heartbeat.feature.githubconnector.impl.data.ProcessCliRunner
import io.aequicor.heartbeat.feature.githubconnector.impl.data.ProcessGitHubCli
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubCli
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubConnectionStore
import kotlinx.coroutines.launch

private val log = Log.tag("GitHubConnectorBindings")

/** The connector machine belongs to the profile and starts when the screen first injects it. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object GitHubConnectorBindings {
    /** The user's GitHub CLI. */
    @Provides
    internal fun cli(dispatchers: DispatcherProvider): GitHubCli =
        ProcessGitHubCli(ProcessCliRunner(dispatchers), CliHost())

    /** The machine; the saved connection is read right after the start. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        cli: GitHubCli,
        store: GitHubConnectionStore,
    ): Machine<GitHubConnectorState, GitHubConnectorIntent, GitHubConnectorOutput> {
        val machine = launcher.launch(GitHubConnectorMachineSpec, scope, GitHubConnectorEffects(cli, store))
        scope.coroutineScope.launch {
            val result = machine.send(GitHubConnectorIntent.Public.Start)
            log.i { "GitHub connector start: $result" }
        }
        return machine
    }
}
