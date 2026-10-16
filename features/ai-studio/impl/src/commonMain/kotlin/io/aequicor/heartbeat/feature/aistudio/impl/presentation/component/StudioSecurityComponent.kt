package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityMachine
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSecurityModel

/** Settings screen lifetime; the security machine remains owned by the profile. */
@AssistedInject
internal class StudioSecurityComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: StudioSecurityMachine,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    val model: StudioSecurityModel = instanceKeeper.getOrCreate("studio-security-model") {
        RetainedModel(StudioSecurityModel(machine, factory, screen.coroutineScope))
    }.model

    fun close() = navigator.close()

    private class RetainedModel(val model: StudioSecurityModel) : InstanceKeeper.Instance

    @AssistedFactory
    fun interface Factory {
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): StudioSecurityComponent
    }
}
