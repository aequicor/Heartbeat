package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView

/** Context settings component owning only screen lifetime; the settings belong to the profile machine. */
@AssistedInject
internal class ContextSettingsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    view: ContextOptimizationView,
    machines: MachineRegistry,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The settings store, retained across configuration changes. */
    val model: ContextSettingsModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(ContextSettingsModel(view, machines, factory, screen.coroutineScope))
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: ContextSettingsModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned settings screen instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component and screen scope. */
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): ContextSettingsComponent
    }

    private companion object {
        const val MODEL_KEY = "context-settings-model"
    }
}
