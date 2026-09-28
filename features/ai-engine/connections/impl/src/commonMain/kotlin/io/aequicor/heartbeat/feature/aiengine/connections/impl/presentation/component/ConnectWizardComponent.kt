package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.backhandler.BackCallback
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineResult
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardModel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId

/** Navigation entry of the wizard; answers [ConnectEngineResult] when a connection was created. */
@AssistedInject
class ConnectWizardComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    val model: ConnectWizardModel,
) : ComponentContext by context {
    private val log = Log.tag("ConnectWizardComponent")

    init {
        // The wizard owns back: it steps back or rolls back a created connection instead of just popping the entry.
        backHandler.register(
            BackCallback {
                log.i { "system back in wizard" }
                model.store.intent(ConnectWizardScreenIntent.SystemBack)
            },
        )
    }

    /** Closes the wizard, delivering the created binding if any. */
    fun close(binding: String?) {
        log.i { "close wizard connected=${binding != null}" }
        if (binding == null) {
            navigator.close()
        } else {
            navigator.finishWithResult(ConnectEngineResult, EngineBindingId(binding))
        }
    }

    /** Metro factory for a lifecycle-owned wizard. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component. */
        fun create(context: ComponentContext, navigator: Navigator): ConnectWizardComponent
    }
}
