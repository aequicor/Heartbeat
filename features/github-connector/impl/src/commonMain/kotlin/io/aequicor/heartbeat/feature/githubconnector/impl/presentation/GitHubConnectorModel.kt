package io.aequicor.heartbeat.feature.githubconnector.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectStep
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnection
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorIntent
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorOutput
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorState
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

/** Steps of the connection dialog as the screen knows them. */
internal enum class GitHubStepUi { Discovering, ChoosingAccount, LoadingRepositories, ChoosingRepository, Verifying }

/** Reasons a step failed; the screen maps each one to a fixed text. */
internal enum class GitHubFailureUi { CliNotFound, NotLoggedIn, NoAccess, NoPullRequestAccess, Failed }

/** The connected account and repository; the CLI path is not shown. */
@Immutable
internal data class GitHubConnectionUi(val account: String, val repository: String)

/** A repository of the dialog list. */
@Immutable
internal data class GitHubRepositoryUi(val name: String, val isPrivate: Boolean)

/** The connection dialog as the screen draws it. */
@Immutable
internal data class GitHubDialogUi(
    val step: GitHubStepUi,
    val accounts: ImmutableList<String> = persistentListOf(),
    val account: String? = null,
    val repositories: ImmutableList<GitHubRepositoryUi> = persistentListOf(),
    val repository: String? = null,
    val failure: GitHubFailureUi? = null,
) {
    /** Whether the chosen pair can be confirmed now. */
    val canConfirm: Boolean get() = step == GitHubStepUi.ChoosingRepository && repository != null
}

/** A projection of the connector machine plus the local repository search text. */
@Immutable
internal data class GitHubConnectorScreenState(
    val isLoading: Boolean = true,
    val connection: GitHubConnectionUi? = null,
    val failure: GitHubFailureUi? = null,
    val dialog: GitHubDialogUi? = null,
    val query: String = "",
) : MVIState {
    /** Repositories of the dialog that match the search text. */
    val visibleRepositories: ImmutableList<GitHubRepositoryUi>
        get() = dialog?.repositories.orEmpty().filter { it.name.contains(query.trim(), ignoreCase = true) }
            .toImmutableList()
}

/** User actions of the connector screen. */
internal sealed interface GitHubConnectorScreenIntent : MVIIntent {
    /** Opens the connection dialog. */
    data object Connect : GitHubConnectorScreenIntent

    /** Repeats the last failed attempt. */
    data object Retry : GitHubConnectorScreenIntent

    /** Forgets the connection. */
    data object Disconnect : GitHubConnectorScreenIntent

    /** Chooses an account. */
    data class SelectAccount(val account: String) : GitHubConnectorScreenIntent

    /** Chooses a repository. */
    data class SelectRepository(val repository: String) : GitHubConnectorScreenIntent

    /** Changes the repository search text; the machine does not see it. */
    data class Query(val text: String) : GitHubConnectorScreenIntent

    /** Verifies and saves the chosen pair. */
    data object Confirm : GitHubConnectorScreenIntent

    /** Closes the dialog. */
    data object Cancel : GitHubConnectorScreenIntent
}

/** The screen reports only through its state. */
internal sealed interface GitHubConnectorScreenAction : MVIAction

/** Lifecycle-owned FlowMVI projection and intent adapter of the connector machine. */
internal class GitHubConnectorModel(
    machine: Machine<GitHubConnectorState, GitHubConnectorIntent, GitHubConnectorOutput>,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private val log = Log.tag("GitHubConnectorModel")

    private val screen = factory.createScreen<
        GitHubConnectorScreenState,
        GitHubConnectorScreenIntent,
        GitHubConnectorScreenAction,
    >(
        "GitHubConnector",
        GitHubConnectorScreenState().reflecting(machine.state.value),
        // Failures reach the screen through the machine state; a local flag would outlive it with a dead retry button.
        onError = { this },
    ) {
        reflect(machine) { reflecting(it) }
        reduce { intent ->
            when (intent) {
                is GitHubConnectorScreenIntent.Query -> updateState { copy(query = intent.text) }

                else -> {
                    log.i { "GitHub connector action: ${intent::class.simpleName.orEmpty()}" }
                    sendTo(machine, intent.toMachine() ?: return@reduce)
                }
            }
        }
    }

    val store = screen.store

    /** Latest screen state, read synchronously for the first frame. */
    val current = screen.current

    init {
        store.start(scope)
    }
}

private fun GitHubConnectorScreenIntent.toMachine(): GitHubConnectorIntent.Public? = when (this) {
    GitHubConnectorScreenIntent.Connect -> GitHubConnectorIntent.Public.Connect
    GitHubConnectorScreenIntent.Retry -> GitHubConnectorIntent.Public.Retry
    GitHubConnectorScreenIntent.Disconnect -> GitHubConnectorIntent.Public.Disconnect
    is GitHubConnectorScreenIntent.SelectAccount -> GitHubConnectorIntent.Public.SelectAccount(account)
    is GitHubConnectorScreenIntent.SelectRepository -> GitHubConnectorIntent.Public.SelectRepository(repository)
    GitHubConnectorScreenIntent.Confirm -> GitHubConnectorIntent.Public.Confirm
    GitHubConnectorScreenIntent.Cancel -> GitHubConnectorIntent.Public.Cancel
    is GitHubConnectorScreenIntent.Query -> null
}

internal fun GitHubConnectorScreenState.reflecting(state: GitHubConnectorState): GitHubConnectorScreenState =
    when (state) {
        GitHubConnectorState.Loading -> copy(isLoading = true)

        is GitHubConnectorState.Disconnected -> copy(
            isLoading = false,
            connection = null,
            failure = state.failure?.toUi(),
            dialog = null,
            query = "",
        )

        is GitHubConnectorState.Connected -> copy(
            isLoading = false,
            connection = state.connection.toUi(),
            failure = state.failure?.toUi(),
            dialog = null,
            query = "",
        )

        is GitHubConnectorState.Connecting -> copy(
            isLoading = false,
            connection = state.previous?.toUi(),
            failure = null,
            dialog = GitHubDialogUi(
                step = state.step.toUi(),
                accounts = state.accounts.toImmutableList(),
                account = state.account,
                repositories = state.repositories.map { it.toUi() }.toImmutableList(),
                repository = state.repository,
                failure = state.failure?.toUi(),
            ),
        )
    }

private fun GitHubConnection.toUi() = GitHubConnectionUi(account, repository)

private fun GitHubRepository.toUi() = GitHubRepositoryUi(name, isPrivate)

private fun GitHubConnectStep.toUi(): GitHubStepUi = when (this) {
    GitHubConnectStep.Discovering -> GitHubStepUi.Discovering
    GitHubConnectStep.ChoosingAccount -> GitHubStepUi.ChoosingAccount
    GitHubConnectStep.LoadingRepositories -> GitHubStepUi.LoadingRepositories
    GitHubConnectStep.ChoosingRepository -> GitHubStepUi.ChoosingRepository
    GitHubConnectStep.Verifying, GitHubConnectStep.Saving -> GitHubStepUi.Verifying
}

private fun GitHubConnectorFailure.toUi(): GitHubFailureUi = when (this) {
    GitHubConnectorFailure.CliNotFound -> GitHubFailureUi.CliNotFound
    GitHubConnectorFailure.NotLoggedIn -> GitHubFailureUi.NotLoggedIn
    GitHubConnectorFailure.NoAccess -> GitHubFailureUi.NoAccess
    GitHubConnectorFailure.NoPullRequestAccess -> GitHubFailureUi.NoPullRequestAccess
    GitHubConnectorFailure.Failed -> GitHubFailureUi.Failed
}
