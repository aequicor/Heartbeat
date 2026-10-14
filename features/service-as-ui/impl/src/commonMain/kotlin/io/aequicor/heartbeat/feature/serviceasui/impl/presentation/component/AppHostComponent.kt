package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiAppScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppHostModel

/** The pane-owned app and its single document host, retained outside lazy transcript rows. */
@AssistedInject
class AppHostComponent internal constructor(
    @Assisted context: ComponentContext,
    @Assisted navigator: Navigator,
    internal val model: AppHostModel,
    hosts: NavHostFactory,
    @ForScope(ServiceUiAppScope::class) scope: ScopeHandle,
) : ComponentContext by context {
    internal val documents = WebDocumentHosts(
        this,
        navigator,
        hosts,
        scope.coroutineScope,
        model.surfaces,
        isFill = true,
    )

    /** Creates the component within the retained app graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds the route lifecycle and navigator. */
        fun create(context: ComponentContext, navigator: Navigator): AppHostComponent
    }
}
