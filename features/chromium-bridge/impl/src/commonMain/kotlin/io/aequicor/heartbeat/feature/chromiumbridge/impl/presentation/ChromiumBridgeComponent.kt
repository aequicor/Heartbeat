package io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation

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
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeIntent
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeOutput
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeState

/** Settings component owning only the screen's lifetime; the bridge belongs to the profile. */
@AssistedInject
internal class ChromiumBridgeComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: Machine<ChromiumBridgeState, ChromiumBridgeIntent, ChromiumBridgeOutput>,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The settings store, retained across configuration changes. */
    val model: ChromiumBridgeModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(ChromiumBridgeModel(machine, factory, screen.coroutineScope))
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: ChromiumBridgeModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned settings screen instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component and screen scope. */
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): ChromiumBridgeComponent
    }

    private companion object {
        const val MODEL_KEY = "chromium-bridge-model"
    }
}
