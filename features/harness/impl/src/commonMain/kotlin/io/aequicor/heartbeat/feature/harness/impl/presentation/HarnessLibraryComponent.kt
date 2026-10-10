package io.aequicor.heartbeat.feature.harness.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.harness.api.HarnessDetailRoute
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine

private val log = Log.tag("HarnessHost")

/** The library section; the library belongs to the profile and outlives the screen. */
@AssistedInject
internal class HarnessLibraryComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: Lazy<HarnessMachine>,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    val model: HarnessLibraryModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        Retained(
            HarnessLibraryModel(machine.value, factory, screen.coroutineScope),
        )
    }.model

    /** Opens the details of a harness. */
    fun openDetail(harness: String) {
        log.i { "Open harness details" }
        navigator.navigate(HarnessDetailRoute(harness))
    }

    fun close() = navigator.close()

    private class Retained(val model: HarnessLibraryModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned screen instance. */
    @AssistedFactory
    fun interface Factory {
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): HarnessLibraryComponent
    }
}

private const val MODEL_KEY = "harness-model"
