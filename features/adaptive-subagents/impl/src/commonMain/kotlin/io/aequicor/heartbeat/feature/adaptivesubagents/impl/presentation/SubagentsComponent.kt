package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfileRoute
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.LibraryMachine
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.ModelDirectory
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.ResearchAvailability

/** The adaptive subagents section; the library belongs to the profile and outlives the screen. */
@AssistedInject
internal class SubagentsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    machine: LibraryMachine,
    directory: ModelDirectory,
    adaptive: AdaptiveModels,
    research: ResearchAvailability,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The screen store, retained across configuration changes. */
    val model: SubagentsModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        val model = SubagentsModel(
            machine,
            directory.observe(),
            adaptive,
            research.observe(),
            factory,
            screen.coroutineScope,
        ) { key -> navigator.navigate(ModelProfileRoute(key.engine.value, key.model.value)) }
        RetainedModel(model)
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: SubagentsModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned settings screen instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component and screen scope. */
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): SubagentsComponent
    }

    private companion object {
        const val MODEL_KEY = "adaptive-subagents-model"
    }
}
