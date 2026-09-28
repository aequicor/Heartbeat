package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.items.Items
import com.arkivanov.decompose.router.items.Items.ActiveLifecycleState
import com.arkivanov.decompose.router.items.ItemsNavigation
import com.arkivanov.decompose.router.items.childItems
import com.arkivanov.decompose.router.items.navigate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
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
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRoute
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lifecycle-bound navigation component rendering the feature screen. */
@OptIn(ExperimentalDecomposeApi::class) // childItems keeps one questionnaire host per session.
@AssistedInject
class AiStudioComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    val model: AiStudioModel,
    entries: StudioEntries,
    private val hosts: NavHostFactory,
    @ForScope(AiStudioScope::class) scope: ScopeHandle,
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

    private val questionNavigation = ItemsNavigation<String>()

    // One child per session with open questions; a session without questions destroys its child and host.
    private val questionItems = childItems(
        source = questionNavigation,
        serializer = null,
        initialItems = { Items() },
        key = "questions",
    ) { sessionId, context -> questionHost(context, sessionId) }

    private val questionHosts = MutableStateFlow<Map<String, StackHost>>(emptyMap())

    /**
     * Questionnaire hosts by session (toggle `questionnaire.enabled`): each session with open questions gets its own
     * nested host, shown inside the pane of that session, and loses it once the session has no open questions.
     */
    val questions: StateFlow<Map<String, StackHost>> = questionHosts.asStateFlow()

    init {
        val hostsWatch = questionItems.subscribe { children ->
            val next = children.activeItems.mapValues { (_, child) -> child.first }
            log.d { "questionHosts: ${questionHosts.value.size} -> ${next.size}" }
            questionHosts.value = next
        }
        // The feature scope runs on the main dispatcher, where Decompose navigation must happen.
        val watching = scope.coroutineScope.launch {
            entries.questionSources.collect { sources ->
                log.i { "Show questionnaires of sessions count=${sources.size}" }
                val shown = sources.toList()
                questionNavigation.navigate { Items(shown, shown.associateWith { ActiveLifecycleState.RESUMED }) }
            }
        }
        lifecycle.doOnDestroy {
            watching.cancel()
            hostsWatch.cancel()
        }
    }

    private fun questionHost(context: ComponentContext, sessionId: String): StackHost = hosts.stack(
        context = context,
        parent = navigator,
        name = "questions-$sessionId",
        initial = listOf(QuestionnaireRoute(sessionId)),
        local = emptyList(),
        global = GlobalRoutes.Only(setOf(QuestionnaireRoute::class)),
    )

    /** Whether the engine connection settings are offered. */
    val showsConnections: Flow<Boolean> = entries.showsConnections

    /** Whether the profile search settings are offered. */
    val showsProfileSettings: Flow<Boolean> = entries.showsProfileSettings

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    /** Opens the feature toggles panel above the studio; the studio keeps its state underneath. */
    fun openToggles() = navigator.navigate(
        TogglesPanelRoute,
        NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
    )

    /** Opens profile-owned search provider settings. */
    fun openProfileSettings() {
        log.i { "open profile settings" }
        navigator.navigate(ProfileSettingsRoute, NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade))
    }

    /** Opens the engine × connection × model settings of the active profile above the studio. */
    fun openConnections() {
        log.i { "open engine connections" }
        navigator.navigate(EngineConnectionsRoute, NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade))
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
