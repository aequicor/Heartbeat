package io.aequicor.heartbeat.feature.computeruse.impl.presentation

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
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore

/**
 * Panel component owning only screen lifetime: the capture, the frames and the machine stay profile-owned, so
 * closing the panel neither stops an agent's capture nor deletes its frames.
 */
@AssistedInject
internal class ComputerUseComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>,
    frames: FrameStore,
    preferences: ComputerUsePreferences,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The panel store, retained across configuration changes. */
    val model: ComputerUseModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(ComputerUseModel(machine, frames, preferences, factory, screen.coroutineScope))
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: ComputerUseModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned panel instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component and screen scope. */
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): ComputerUseComponent
    }

    private companion object {
        const val MODEL_KEY = "computer-use-model"
    }
}
