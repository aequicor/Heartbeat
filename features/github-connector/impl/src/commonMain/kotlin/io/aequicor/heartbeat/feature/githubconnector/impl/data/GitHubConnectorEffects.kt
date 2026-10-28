package io.aequicor.heartbeat.feature.githubconnector.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnection
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorEffect
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorIntent
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubAccess
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubCli
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubCliException
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubConnectionStore
import kotlinx.coroutines.CancellationException

/**
 * Runs the connector steps against the CLI and the store and reports typed results; raw CLI text is never logged.
 * Verification and saving are separate effects: a result the machine no longer wants (a cancelled dialog) is never
 * persisted.
 */
internal class GitHubConnectorEffects(private val cli: GitHubCli, private val store: GitHubConnectionStore) :
    EffectHandler<GitHubConnectorEffect, GitHubConnectorIntent> {
    private val log = Log.tag("GitHubConnectorEffects")

    override suspend fun handle(effect: GitHubConnectorEffect, machine: EffectScope<GitHubConnectorIntent>) {
        when (effect) {
            GitHubConnectorEffect.Restore -> machine.send(GitHubConnectorIntent.Internal.Restored(store.load()))

            GitHubConnectorEffect.Forget -> {
                store.clear()
                log.i { "GitHub connection removed" }
                machine.send(GitHubConnectorIntent.Internal.Forgotten)
            }

            is GitHubConnectorEffect.Save -> {
                store.save(effect.connection)
                log.i { "GitHub connected: repository ${effect.connection.repository}" }
                machine.send(GitHubConnectorIntent.Internal.Saved(effect.connection))
            }

            is GitHubConnectorEffect.Discover -> step(machine) {
                val found = cli.locate(effect.preferredCli) ?: throw GitHubCliException(
                    GitHubConnectorFailure.CliNotFound,
                )
                GitHubConnectorIntent.Internal.Discovered(found, cli.accounts(found))
            }

            is GitHubConnectorEffect.LoadRepositories -> step(machine) {
                GitHubConnectorIntent.Internal.RepositoriesLoaded(
                    effect.account,
                    cli.repositories(effect.cli, effect.account),
                )
            }

            is GitHubConnectorEffect.Verify -> step(machine) {
                when (cli.access(effect.cli, effect.account, effect.repository)) {
                    GitHubAccess.Granted -> GitHubConnectorIntent.Internal.Verified(
                        GitHubConnection(effect.account, effect.repository, effect.cli),
                    )

                    GitHubAccess.NoAccess -> GitHubConnectorIntent.Internal.StepFailed(
                        GitHubConnectorFailure.NoAccess,
                    )

                    GitHubAccess.NoPullRequestAccess -> GitHubConnectorIntent.Internal.StepFailed(
                        GitHubConnectorFailure.NoPullRequestAccess,
                    )
                }
            }
        }
    }

    private suspend fun step(machine: EffectScope<GitHubConnectorIntent>, run: suspend () -> GitHubConnectorIntent) {
        val result = try {
            run()
        } catch (error: CancellationException) {
            throw error
        } catch (error: GitHubCliException) {
            log.w(error.redactedFailure()) { "GitHub connector step failed: ${error.failure}" }
            GitHubConnectorIntent.Internal.StepFailed(error.failure)
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "GitHub connector step failed" }
            GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.Failed)
        }
        machine.send(result)
    }
}
