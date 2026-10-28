package io.aequicor.heartbeat.feature.autocontinue.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.autocontinue.impl.domain.AutoContinueMachine

/** Auto-continue settings component owning only screen lifetime; the settings belong to the profile machine. */
@AssistedInject
internal class AutoContinueSettingsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: AutoContinueMachine,
    selections: ModelSelections,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The settings store, retained across configuration changes. */
    val model: AutoContinueSettingsModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(AutoContinueSettingsModel(machine, selections, factory, screen.coroutineScope))
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: AutoContinueSettingsModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned settings screen instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component and screen scope. */
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): AutoContinueSettingsComponent
    }

    private companion object {
        const val MODEL_KEY = "auto-continue-settings-model"
    }
}
