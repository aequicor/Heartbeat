package io.aequicor.heartbeat.feature.worktreemode.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.worktreemode.impl.domain.BuildLimits

/** Build settings component owning only screen lifetime; the limit belongs to the application. */
@AssistedInject
internal class BuildCapacityComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    limits: BuildLimits,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The settings store, retained across configuration changes. */
    val model: BuildCapacityModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(BuildCapacityModel(limits, factory, screen.coroutineScope))
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: BuildCapacityModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned settings screen instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component and screen scope. */
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): BuildCapacityComponent
    }

    private companion object {
        const val MODEL_KEY = "build-capacity-model"
    }
}
