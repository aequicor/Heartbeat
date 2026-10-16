package io.aequicor.heartbeat.feature.projectconfig.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigDialogs
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectConfigMachine

/** Configuration settings component; the machine belongs to the profile and outlives the screen. */
@AssistedInject
internal class ProjectConfigComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: ProjectConfigMachine,
    workspaces: LocalWorkspaces,
    sections: Set<ConfigSection>,
    dialogs: ConfigDialogs,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The screen store, retained across configuration changes. */
    val model: ProjectConfigModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(ProjectConfigModel(machine, workspaces, sections, dialogs, factory, screen.coroutineScope))
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: ProjectConfigModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned settings screen instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component and screen scope. */
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): ProjectConfigComponent
    }

    private companion object {
        const val MODEL_KEY = "project-config-model"
    }
}
