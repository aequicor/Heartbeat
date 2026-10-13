package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiCardScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardModel

/**
 * Navigation entry of one card in a response, embedded by the message that shows it. It hosts the browser documents
 * of the card's modals.
 */
@AssistedInject
class CardComponent internal constructor(
    @Assisted context: ComponentContext,
    @Assisted navigator: Navigator,
    internal val model: CardModel,
    hosts: NavHostFactory,
    @ForScope(ServiceUiCardScope::class) scope: ScopeHandle,
) : ComponentContext by context {
    internal val documents = WebDocumentHosts(this, navigator, hosts, scope.coroutineScope, model.surfaces)

    /** Creates the component within its retained graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose and the navigator of the entry. */
        fun create(context: ComponentContext, navigator: Navigator): CardComponent
    }
}
