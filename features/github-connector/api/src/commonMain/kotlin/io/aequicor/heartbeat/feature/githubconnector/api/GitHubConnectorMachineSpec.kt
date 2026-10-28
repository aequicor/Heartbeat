package io.aequicor.heartbeat.feature.githubconnector.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/** The spec type of the connector machine. */
public typealias GitHubConnectorSpec =
    MachineSpec<GitHubConnectorState, GitHubConnectorIntent, GitHubConnectorEffect, GitHubConnectorOutput>

/**
 * The connector walks from the saved connection through the connection dialog; effects find the CLI, list accounts
 * and repositories and verify the choice. Closing the dialog leaves late results without a matching state, so they
 * are ignored. Nothing but the final [GitHubConnection] is persisted, and it holds no credential.
 *
 * | From | Intent | Guard | To | Effect |
 * |---|---|---|---|---|
 * | Loading | Start | — | Loading | Restore |
 * | Loading | Restored | — | Connected / Disconnected | — |
 * | Disconnected, Connected | Connect, Retry | Retry needs a failure | Connecting(Discovering) | Discover |
 * | Connecting(Discovering) | Discovered | has accounts | Connecting(LoadingRepositories) | LoadRepositories |
 * | Connecting(Discovering) | Discovered | no accounts | Disconnected / Connected (NotLoggedIn) | — |
 * | Connecting(Choosing*) | SelectAccount | known account | LoadingRepositories | LoadRepositories |
 * | Connecting(LoadingRepositories) | RepositoriesLoaded | same account | ChoosingRepository | — |
 * | Connecting(ChoosingRepository) | SelectRepository | listed repository | same step | — |
 * | Connecting(ChoosingRepository) | Confirm | repository chosen | Verifying | Verify |
 * | Connecting(Verifying) | Verified | — | Connecting(Saving) | Save |
 * | Connecting(Saving) | Saved | — | Connected | — |
 * | Connecting(Discovering) | StepFailed | — | Disconnected / Connected with failure | — |
 * | Connecting(Loading…, Verifying) | StepFailed | — | previous Choosing step with failure | — |
 * | Connecting | Cancel | not while Saving | Connected(previous) / Disconnected | — |
 * | Connected | Disconnect | — | Connected until Forgotten | Forget |
 * | Connected | Forgotten | — | Disconnected | — |
 * | Connected | StepFailed | forget failed | Connected with failure | — |
 * | Loading | StepFailed | restore failed | Disconnected with failure | — |
 */
public val GitHubConnectorMachineSpec: GitHubConnectorSpec =
    machineSpec(GitHubConnectorMachineKey, GitHubConnectorState.Loading) {
        state<GitHubConnectorState.Loading> {
            on<GitHubConnectorIntent.Public.Start> { effect { GitHubConnectorEffect.Restore } }
            on<GitHubConnectorIntent.Internal.StepFailed> {
                goto<GitHubConnectorState.Disconnected> { GitHubConnectorState.Disconnected(intent.failure) }
            }
            on<GitHubConnectorIntent.Internal.Restored>(guard = { intent.connection != null }) {
                goto<GitHubConnectorState.Connected> {
                    GitHubConnectorState.Connected(requireNotNull(intent.connection))
                }
            }
            on<GitHubConnectorIntent.Internal.Restored>(guard = { intent.connection == null }) {
                goto<GitHubConnectorState.Disconnected> { GitHubConnectorState.Disconnected() }
            }
        }
        state<GitHubConnectorState.Disconnected> {
            on<GitHubConnectorIntent.Public.Connect> {
                goto<GitHubConnectorState.Connecting> { discovering(null) }
                effect { GitHubConnectorEffect.Discover(null) }
            }
            on<GitHubConnectorIntent.Public.Retry>(guard = { state.failure != null }) {
                goto<GitHubConnectorState.Connecting> { discovering(null) }
                effect { GitHubConnectorEffect.Discover(null) }
            }
        }
        state<GitHubConnectorState.Connected> {
            on<GitHubConnectorIntent.Public.Connect> {
                goto<GitHubConnectorState.Connecting> { discovering(state.connection) }
                effect { GitHubConnectorEffect.Discover(state.connection.cli) }
            }
            on<GitHubConnectorIntent.Public.Retry>(guard = { state.failure != null }) {
                goto<GitHubConnectorState.Connecting> { discovering(state.connection) }
                effect { GitHubConnectorEffect.Discover(state.connection.cli) }
            }
            on<GitHubConnectorIntent.Public.Disconnect> {
                stay { state.copy(failure = null) }
                effect { GitHubConnectorEffect.Forget }
            }
            on<GitHubConnectorIntent.Internal.Forgotten> {
                goto<GitHubConnectorState.Disconnected> { GitHubConnectorState.Disconnected() }
            }
            on<GitHubConnectorIntent.Internal.StepFailed> {
                stay { state.copy(failure = intent.failure) }
            }
        }
        state<GitHubConnectorState.Connecting> {
            on<GitHubConnectorIntent.Internal.Discovered>(guard = {
                state.step == GitHubConnectStep.Discovering && intent.accounts.isNotEmpty()
            }) {
                goto<GitHubConnectorState.Connecting> {
                    val account = state.previous?.account?.takeIf { it in intent.accounts } ?: intent.accounts.first()
                    state.copy(
                        step = GitHubConnectStep.LoadingRepositories,
                        cli = intent.cli,
                        accounts = intent.accounts,
                        account = account,
                    )
                }
                effect {
                    val account = state.previous?.account?.takeIf { it in intent.accounts } ?: intent.accounts.first()
                    GitHubConnectorEffect.LoadRepositories(intent.cli, account)
                }
            }
            on<GitHubConnectorIntent.Internal.Discovered>(guard = {
                state.step == GitHubConnectStep.Discovering && intent.accounts.isEmpty() && state.previous == null
            }) {
                goto<GitHubConnectorState.Disconnected> { GitHubConnectorState.Disconnected(NOT_LOGGED_IN) }
            }
            on<GitHubConnectorIntent.Internal.Discovered>(guard = {
                state.step == GitHubConnectStep.Discovering && intent.accounts.isEmpty() && state.previous != null
            }) {
                goto<GitHubConnectorState.Connected> {
                    GitHubConnectorState.Connected(requireNotNull(state.previous), NOT_LOGGED_IN)
                }
            }
            on<GitHubConnectorIntent.Public.SelectAccount>(guard = {
                state.step in CHOOSING && state.cli != null && intent.account in state.accounts
            }) {
                goto<GitHubConnectorState.Connecting> {
                    state.copy(
                        step = GitHubConnectStep.LoadingRepositories,
                        account = intent.account,
                        repositories = emptyList(),
                        repository = null,
                        failure = null,
                    )
                }
                effect { GitHubConnectorEffect.LoadRepositories(requireNotNull(state.cli), intent.account) }
            }
            on<GitHubConnectorIntent.Internal.RepositoriesLoaded>(guard = {
                state.step == GitHubConnectStep.LoadingRepositories && state.account == intent.account
            }) {
                goto<GitHubConnectorState.Connecting> {
                    val names = intent.repositories.map { it.name }
                    state.copy(
                        step = GitHubConnectStep.ChoosingRepository,
                        repositories = intent.repositories,
                        repository = state.previous?.repository?.takeIf { it in names },
                        failure = null,
                    )
                }
            }
            on<GitHubConnectorIntent.Public.SelectRepository>(guard = {
                state.step == GitHubConnectStep.ChoosingRepository &&
                    state.repositories.any { it.name == intent.repository }
            }) {
                stay { state.copy(repository = intent.repository, failure = null) }
            }
            on<GitHubConnectorIntent.Public.Confirm>(guard = {
                state.step == GitHubConnectStep.ChoosingRepository && state.repository != null
            }) {
                goto<GitHubConnectorState.Connecting> { state.copy(step = GitHubConnectStep.Verifying, failure = null) }
                effect {
                    GitHubConnectorEffect.Verify(
                        requireNotNull(state.cli),
                        requireNotNull(state.account),
                        requireNotNull(state.repository),
                    )
                }
            }
            on<GitHubConnectorIntent.Internal.Verified>(guard = { state.step == GitHubConnectStep.Verifying }) {
                goto<GitHubConnectorState.Connecting> { state.copy(step = GitHubConnectStep.Saving) }
                effect { GitHubConnectorEffect.Save(intent.connection) }
            }
            on<GitHubConnectorIntent.Internal.Saved>(guard = { state.step == GitHubConnectStep.Saving }) {
                goto<GitHubConnectorState.Connected> { GitHubConnectorState.Connected(intent.connection) }
            }
            on<GitHubConnectorIntent.Internal.StepFailed>(guard = {
                state.step == GitHubConnectStep.Discovering && state.previous == null
            }) {
                goto<GitHubConnectorState.Disconnected> { GitHubConnectorState.Disconnected(intent.failure) }
            }
            on<GitHubConnectorIntent.Internal.StepFailed>(guard = {
                state.step == GitHubConnectStep.Discovering && state.previous != null
            }) {
                goto<GitHubConnectorState.Connected> {
                    GitHubConnectorState.Connected(requireNotNull(state.previous), intent.failure)
                }
            }
            on<GitHubConnectorIntent.Internal.StepFailed>(guard = {
                state.step == GitHubConnectStep.LoadingRepositories
            }) {
                goto<GitHubConnectorState.Connecting> {
                    state.copy(step = GitHubConnectStep.ChoosingAccount, failure = intent.failure)
                }
            }
            on<GitHubConnectorIntent.Internal.StepFailed>(guard = {
                state.step == GitHubConnectStep.Verifying || state.step == GitHubConnectStep.Saving
            }) {
                goto<GitHubConnectorState.Connecting> {
                    state.copy(step = GitHubConnectStep.ChoosingRepository, failure = intent.failure)
                }
            }
            on<GitHubConnectorIntent.Public.Cancel>(guard = {
                state.previous == null && state.step != GitHubConnectStep.Saving
            }) {
                goto<GitHubConnectorState.Disconnected> { GitHubConnectorState.Disconnected() }
            }
            on<GitHubConnectorIntent.Public.Cancel>(guard = {
                state.previous != null && state.step != GitHubConnectStep.Saving
            }) {
                goto<GitHubConnectorState.Connected> {
                    GitHubConnectorState.Connected(requireNotNull(state.previous))
                }
            }
        }
        onEffectFailure { _, _ ->
            // Every failed effect surfaces in the state that is current: a lost restore, save or forget must not
            // look like success.
            GitHubConnectorIntent.Internal.StepFailed(GitHubConnectorFailure.Failed)
        }
    }

private val NOT_LOGGED_IN = GitHubConnectorFailure.NotLoggedIn

private val CHOOSING = setOf(GitHubConnectStep.ChoosingAccount, GitHubConnectStep.ChoosingRepository)

private fun discovering(previous: GitHubConnection?) =
    GitHubConnectorState.Connecting(GitHubConnectStep.Discovering, previous)
