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
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.LibraryMachine
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.ModelDirectory

/** The description editor of one model; it closes itself after a save or a removal. */
@AssistedInject
internal class ProfileEditorComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    @Assisted key: ModelKey,
    machine: LibraryMachine,
    directory: ModelDirectory,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context {
    /** The editor store, retained across configuration changes. */
    val model: ProfileEditorModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(
            ProfileEditorModel(machine, key, directory.observe(), ::close, factory, screen.coroutineScope),
        )
    }.model

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    private class RetainedModel(val model: ProfileEditorModel) : InstanceKeeper.Instance

    /** Metro factory for a lifecycle-owned editor instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates the editor of [key] owned by the supplied component and screen scope. */
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            screen: ScopeHandle,
            key: ModelKey,
        ): ProfileEditorComponent
    }

    private companion object {
        const val MODEL_KEY = "model-profile-editor"
    }
}
