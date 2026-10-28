package io.aequicor.heartbeat.feature.githubconnector.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The GitHub connector of Heartbeat tools; a profile route shown as a section of the settings window. */
@Serializable
@SerialName("github-connector")
public data class GitHubConnectorRoute(val isEmbedded: Boolean = true) : Route

/** Shows the connector section in settings; the PR tools themselves keep their own switch. */
public val GitHubConnectorEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "github_connector.enabled",
    description = "Коннектор GitHub в настройках инструментов Heartbeat (Desktop)",
    default = true,
)

/**
 * The saved connection: the GitHub CLI login and the single repository the PR tools may work with.
 * No credential is stored: the token is requested from [cli] for [account] at every operation.
 */
@Serializable
public data class GitHubConnection(val account: String, val repository: String, val cli: String) {
    override fun toString(): String = "GitHubConnection(***)"
}

/** Read side of the saved connection for the tools that use it; the connector screen owns the writes. */
public fun interface GitHubConnectionSource {
    /** The saved connection of the active profile, or `null` while GitHub is not connected. */
    public suspend fun current(): GitHubConnection?
}

/** A repository the selected account can reach. */
@Serializable
public data class GitHubRepository(val name: String, val isPrivate: Boolean)

/** Why a connector step failed; the screen shows a fixed text, never CLI output. */
public enum class GitHubConnectorFailure {
    /** The GitHub CLI was not found in the standard locations or on the user's PATH. */
    CliNotFound,

    /** The CLI has no logged-in github.com account. */
    NotLoggedIn,

    /** The selected account cannot read the repository or the CLI refused its token. */
    NoAccess,

    /** The account can read the repository but cannot create pull requests in it. */
    NoPullRequestAccess,

    /** Any other failure; the user can retry. */
    Failed,
}

/** Steps of the connection dialog. */
public enum class GitHubConnectStep {
    /** Looking for the CLI and its accounts. */
    Discovering,

    /** Accounts are listed; none of them loads repositories yet. */
    ChoosingAccount,

    /** Repositories of the selected account are being loaded. */
    LoadingRepositories,

    /** Repositories are listed and one can be chosen. */
    ChoosingRepository,

    /** The chosen pair is being checked. */
    Verifying,

    /** The verified pair is being saved; the dialog cannot be cancelled until it is. */
    Saving,
}

/** One connector instance per profile. */
public sealed interface GitHubConnectorState : MachineState {
    /** The saved connection is being read. */
    public data object Loading : GitHubConnectorState

    /** Nothing is connected; [failure] is the reason the last attempt ended. */
    public data class Disconnected(val failure: GitHubConnectorFailure? = null) : GitHubConnectorState

    /** A repository is connected; [failure] is the reason the last change attempt ended. */
    public data class Connected(val connection: GitHubConnection, val failure: GitHubConnectorFailure? = null) :
        GitHubConnectorState

    /** The dialog is open; [previous] is restored on cancel. */
    public data class Connecting(
        val step: GitHubConnectStep,
        val previous: GitHubConnection? = null,
        val cli: String? = null,
        val accounts: List<String> = emptyList(),
        val account: String? = null,
        val repositories: List<GitHubRepository> = emptyList(),
        val repository: String? = null,
        val failure: GitHubConnectorFailure? = null,
    ) : GitHubConnectorState
}

/** Requests from the screen and results of effects. */
public sealed interface GitHubConnectorIntent : MachineIntent {
    /** Requests accepted from the screen or another feature. */
    public sealed interface Public : GitHubConnectorIntent {
        /** Reads the saved connection once, when the profile starts. */
        public data object Start : Public

        /** Opens the connection dialog. */
        public data object Connect : Public

        /** Repeats the last failed attempt from the start. */
        public data object Retry : Public

        /** Chooses the account and loads its repositories. */
        public data class SelectAccount(val account: String) : Public

        /** Chooses the repository of the loaded list. */
        public data class SelectRepository(val repository: String) : Public

        /** Checks and saves the chosen account and repository. */
        public data object Confirm : Public

        /** Closes the dialog without changes. */
        public data object Cancel : Public

        /** Forgets the connection. */
        public data object Disconnect : Public
    }

    /** Results accepted only from this machine's effects. */
    public sealed interface Internal : GitHubConnectorIntent {
        /** The saved connection, or `null` when none was saved. */
        public data class Restored(val connection: GitHubConnection?) : Internal

        /** The CLI and its logged-in accounts were found. */
        public data class Discovered(val cli: String, val accounts: List<String>) : Internal

        /** The repositories of [account] were loaded. */
        public data class RepositoriesLoaded(val account: String, val repositories: List<GitHubRepository>) : Internal

        /** The pair was verified; the machine saves it after accepting this result. */
        public data class Verified(val connection: GitHubConnection) : Internal

        /** The verified pair was saved, so the tools see it. */
        public data class Saved(val connection: GitHubConnection) : Internal

        /** The saved connection was deleted. */
        public data object Forgotten : Internal

        /** The running step failed. */
        public data class StepFailed(val failure: GitHubConnectorFailure) : Internal
    }
}

/** Commands executed by the Desktop backend. */
public sealed interface GitHubConnectorEffect : MachineEffect {
    /** Reads the saved connection. */
    public data object Restore : GitHubConnectorEffect

    /** Finds the CLI and lists its accounts, preferring [preferredCli]. */
    public data class Discover(val preferredCli: String?) : GitHubConnectorEffect

    /** Lists the repositories [account] can reach. */
    public data class LoadRepositories(val cli: String, val account: String) : GitHubConnectorEffect

    /** Checks access of [account] to [repository]; nothing is saved until the machine accepts the result. */
    public data class Verify(val cli: String, val account: String, val repository: String) : GitHubConnectorEffect

    /** Saves the verified connection. */
    public data class Save(val connection: GitHubConnection) : GitHubConnectorEffect

    /** Deletes the saved connection. */
    public data object Forget : GitHubConnectorEffect
}

/** The connector reports through its state only. */
public sealed interface GitHubConnectorOutput : MachineOutput

/** Address of the profile's GitHub connector. */
public object GitHubConnectorMachineKey :
    MachineKey<
        GitHubConnectorState,
        GitHubConnectorIntent,
        GitHubConnectorIntent.Public,
        GitHubConnectorEffect,
        GitHubConnectorOutput,
    > {
    override val name: String = "github-connector"
}
