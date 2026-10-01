package io.aequicor.heartbeat.feature.researchchat.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.core.navigation.routeEntry
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentPreviewRoute
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsPickRoute
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsPicked
import io.aequicor.heartbeat.feature.researchchat.impl.di.scope.ResearchChatScope
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchModel
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenAction
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Navigation entry retaining the feature graph while the profile owns native generation. */
@AssistedInject
class ResearchComponent internal constructor(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    internal val model: ResearchModel,
    hosts: NavHostFactory,
    @ForScope(ResearchChatScope::class) scope: ScopeHandle,
) : ComponentContext by context {
    /** Separate modal stack leaves the research workspace and studio shell composed underneath. */
    internal val dialogs: StackHost = hosts.stack(
        context = this,
        parent = navigator,
        name = "research-attachments",
        initial = listOf(ResearchDialogBase),
        local = listOf(routeEntry<ResearchDialogBase> { _, _, _ -> ResearchNoDialog }),
        global = GlobalRoutes.Only(setOf(AttachmentsPickRoute::class, AttachmentPreviewRoute::class)),
    )

    init {
        scope.coroutineScope.launch {
            dialogs.navigator.results(AttachmentsPicked).collect {
                model.store.intent(ResearchScreenIntent.FilesPicked(it.requestId, it.attachments))
            }
        }
    }

    internal fun handle(action: ResearchScreenAction) {
        when (action) {
            is ResearchScreenAction.PickFiles -> dialogs.navigator.navigateForResult(
                AttachmentsPickRoute(action.requestId, action.support),
                AttachmentsPicked,
                NavOptions(transition = NavTransition.None),
            )

            is ResearchScreenAction.OpenAttachment -> dialogs.navigator.navigate(
                AttachmentPreviewRoute(AttachmentId(action.id)),
                NavOptions(transition = NavTransition.None),
            )

            is ResearchScreenAction.SaveAttachment -> dialogs.navigator.navigate(
                AttachmentPreviewRoute(
                    AttachmentId(action.id),
                    isExportOnOpen = true,
                ),
                NavOptions(transition = NavTransition.None),
            )
        }
    }

    /** Returns to the studio while profile-owned runs continue. */
    fun close() = navigator.close()

    /** Creates the navigation component within its retained feature graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose and its local navigator. */
        fun create(context: ComponentContext, navigator: Navigator): ResearchComponent
    }
}

/** Empty base of the local modal stack; the research renderer omits it. */
@Serializable
@SerialName("research_chat.dialog_base")
internal data object ResearchDialogBase : Route

/** Marker for the local modal stack while no file dialog is open. */
internal data object ResearchNoDialog : NavComponent
