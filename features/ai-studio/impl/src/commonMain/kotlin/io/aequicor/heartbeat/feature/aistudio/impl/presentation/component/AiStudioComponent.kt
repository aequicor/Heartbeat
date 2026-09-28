package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.core.navigation.routeEntry
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lifecycle-bound navigation component rendering the feature screen. */
@AssistedInject
class AiStudioComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    val model: AiStudioModel,
    entries: StudioEntries,
    hosts: NavHostFactory,
) : ComponentContext by context {
    private val log = Log.tag("AiStudioComponent")

    /**
     * The chat area of the studio. Its base entry is the studio's own chat panes; research opens on top of it,
     * so the sidebar and the rest of the studio stay in place while the chat area switches its layout.
     */
    val workspace: StackHost = hosts.stack(
        context = this,
        parent = navigator,
        name = "workspace",
        initial = listOf(StudioChatRoute),
        local = listOf(routeEntry<StudioChatRoute> { _, _, _ -> StudioChat }),
        global = GlobalRoutes.Only(setOf(ResearchChatRoute::class)),
    )

    /** Whether the engine connection settings are offered. */
    val showsConnections: Flow<Boolean> = entries.showsConnections

    /** Whether the profile search settings are offered. */
    val showsProfileSettings: Flow<Boolean> = entries.showsProfileSettings

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    /** Opens the feature toggles panel above the studio; the studio keeps its state underneath. */
    fun openToggles() = navigator.navigate(
        TogglesPanelRoute(),
        NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
    )

    /** Opens profile-owned search provider settings. */
    fun openProfileSettings() {
        log.i { "open profile settings" }
        navigator.navigate(ProfileSettingsRoute(), NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade))
    }

    /** Opens the engine × connection × model settings of the active profile above the studio. */
    fun openConnections() {
        log.i { "open engine connections" }
        navigator.navigate(
            EngineConnectionsRoute(),
            NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
        )
    }

    /** Switches the chat area to the projectless research layout for the selected Koog route. */
    fun openResearch(modelId: String) {
        val target = try {
            Json.decodeFromString(EngineTarget.serializer(), modelId)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Invalid research model selection" }
            return
        }
        if (target.engine != KoogEngineId) return
        log.i { "Open research chat" }
        workspace.navigator.navigate(
            ResearchChatRoute(target),
            NavOptions(LaunchMode.SingleTop, NavTarget.Nearest, NavTransition.Fade),
        )
    }

    /** Metro factory for a lifecycle-owned feature instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun create(context: ComponentContext, navigator: Navigator): AiStudioComponent
    }
}

/** Base entry of [AiStudioComponent.workspace]: the studio renders its own chat panes for it. */
@Serializable
@SerialName("aistudio_chat")
internal data object StudioChatRoute : Route

/** Marker component of [StudioChatRoute]; the studio screen draws the panes itself. */
internal data object StudioChat : NavComponent
