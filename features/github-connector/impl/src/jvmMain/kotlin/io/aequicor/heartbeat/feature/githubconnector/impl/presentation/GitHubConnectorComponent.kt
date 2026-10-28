package io.aequicor.heartbeat.feature.githubconnector.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorIntent
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorOutput
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorState

/** Owns only the screen lifetime; the connection belongs to the profile machine. */
@AssistedInject
internal class GitHubConnectorComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: Machine<GitHubConnectorState, GitHubConnectorIntent, GitHubConnectorOutput>,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The screen store, retained across configuration changes. */
    val model: GitHubConnectorModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(GitHubConnectorModel(machine, factory, screen.coroutineScope))
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: GitHubConnectorModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned screen instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component and screen scope. */
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): GitHubConnectorComponent
    }

    private companion object {
        const val MODEL_KEY = "github-connector-model"
    }
}
