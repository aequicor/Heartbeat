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
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioAttachmentNavigation
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSurfaceBinding
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.toUi
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentPreviewRoute
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsPickRoute
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsPicked
import io.aequicor.heartbeat.feature.browser.api.BrowserDisplay
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserRoute
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.checklist.api.ChecklistRoute
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRoute
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiCardRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSlotRoute
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    @ForScope(AiStudioScope::class) private val scope: ScopeHandle,
    private val machines: MachineRegistry,
    private val surfaces: StudioSurfaces,
    private val surfacePanes: StudioSurfaceBinding,
) : ComponentContext by context {
    private val log = Log.tag("AiStudioComponent")

    /**
     * The chat area of the studio. Its base entry is the studio's own chat panes; research opens on
     * top of it, so the sidebar and the rest of the studio stay in place while the chat area
     * switches its layout.
     */
    val workspace: StackHost = hosts.stack(
        context = this,
        parent = navigator,
        name = "workspace",
        initial = listOf(StudioChatRoute),
        local = listOf(routeEntry<StudioChatRoute> { _, _, _ -> StudioChat }),
        global = GlobalRoutes.Only(setOf(ResearchChatRoute::class)),
    )
    private val workspaceNavigation = StudioWorkspaceNavigation(workspace.navigator)

    /** Browser has its own navigation host, independent of the chat/research workspace. */
    val browser: StackHost =
        hosts.stack(
            context = this,
            parent = navigator,
            name = "browser-panel",
            initial = listOf(BrowserRoute),
            local = emptyList(),
            global = GlobalRoutes.Only(setOf(BrowserRoute::class)),
        )
    private val browserPresentation = MutableStateFlow(BrowserPaneMode.Hidden)
    val browserMode: StateFlow<BrowserPaneMode> = browserPresentation
    private val browserBack = StudioBrowserBack(::hideBrowser)

    private val side = StudioSidePanel(this, navigator, hosts, browserPresentation, surfaces, scope.coroutineScope)

    /** The interface panel of this window's dock while interfaces are on; it shares the side with the browser. */
    val interfacePanel: StateFlow<StackHost?> = side.panel

    /** What the side of the window shows: the browser or the interface panel opened last. */
    val sidePanel: StateFlow<SidePanelUi> = side.shown

    init {
        // Registered before the dialog host: an open attachment dialog above the browser handles "back" first.
        backHandler.register(browserBack.callback)
        backHandler.register(side.back)
        val presentation = scope.coroutineScope.launch {
            machines.observe(BrowserMachineKey).collectLatest { machine ->
                log.v { "Browser presentation observed" }
                if (machine == null) showBrowser(BrowserPaneMode.Hidden)
                machine?.state?.collect { state ->
                    showBrowser(
                        when ((state as? BrowserState.Running)?.display) {
                            BrowserDisplay.Panel -> BrowserPaneMode.Panel
                            BrowserDisplay.Expanded -> BrowserPaneMode.Expanded
                            BrowserDisplay.Hidden, null -> BrowserPaneMode.Hidden
                        },
                    )
                }
            }
        }
        lifecycle.doOnDestroy {
            presentation.cancel()
            backHandler.unregister(browserBack.callback)
            backHandler.unregister(side.back)
        }
    }

    private fun showBrowser(mode: BrowserPaneMode) {
        val old = browserPresentation.value
        if (old == mode) return
        log.i { "browserPresentation: $old -> $mode" }
        browserPresentation.value = mode
        browserBack.update(mode)
    }

    private fun hideBrowser() {
        log.i { "Hide browser on back" }
        scope.coroutineScope.launch {
            machines.send(BrowserMachineKey, BrowserIntent.Public.Display(BrowserDisplay.Hidden))
        }
    }

    /**
     * Attachment dialogs retain the workspace and sidebar underneath their native modal surface.
     */
    val dialogs: StackHost = hosts.stack(
        context = this,
        parent = navigator,
        name = "attachment-dialogs",
        initial = listOf(StudioNoDialogRoute),
        local = listOf(routeEntry<StudioNoDialogRoute> { _, _, _ -> StudioNoDialog }),
        global = GlobalRoutes.Only(setOf(AttachmentsPickRoute::class, AttachmentPreviewRoute::class)),
    )

    private val checklistNavigation = ItemsNavigation<String>()
    private val checklistItems = childItems(
        source = checklistNavigation,
        serializer = null,
        initialItems = { Items() },
        key = "checklists",
    ) { id, context ->
        hosts.stack(
            context = context,
            parent = navigator,
            name = "checklist-$id",
            initial = listOf(ChecklistRoute(id)),
            local = emptyList(),
            global = GlobalRoutes.Only(setOf(ChecklistRoute::class)),
        )
    }
    private val checklistHosts = MutableStateFlow<Map<String, StackHost>>(emptyMap())

    /** Inline card hosts, resolved without depending on the checklist implementation. */
    val checklists: StateFlow<Map<String, StackHost>> = checklistHosts.asStateFlow()

    private val slotNavigation = ItemsNavigation<String>()
    private val slotItems = childItems(
        source = slotNavigation,
        serializer = null,
        initialItems = { Items() },
        key = "interface-slots",
    ) { key, context ->
        val (paneId, slot) = studioSlotOf(key)
        hosts.stack(
            context = context,
            parent = navigator,
            name = "interface-slot-$key",
            initial = listOf(ServiceUiSlotRoute(surfaces.host(paneId), slot)),
            local = emptyList(),
            global = GlobalRoutes.Only(setOf(ServiceUiSlotRoute::class)),
        )
    }
    private val slotHosts = MutableStateFlow<Map<String, StackHost>>(emptyMap())

    private val cardNavigation = ItemsNavigation<String>()
    private val cardItems = childItems(
        source = cardNavigation,
        serializer = null,
        initialItems = { Items() },
        key = "interface-cards",
    ) { key, context ->
        val (paneId, cardId) = key.split(':', limit = 2)
        hosts.stack(
            context = context,
            parent = navigator,
            name = "interface-card-$key",
            initial = listOf(ServiceUiCardRoute(cardId, surfaces.host(paneId.toInt()))),
            local = emptyList(),
            global = GlobalRoutes.Only(setOf(ServiceUiCardRoute::class)),
        )
    }
    private val cardHosts = MutableStateFlow<Map<String, StackHost>>(emptyMap())

    /**
     * Hosts of the cards shown in the reply feeds of panes by [studioCardKey], while interfaces are on: a card draws in
     * the place of its pane, so its prompts go to that pane.
     */
    val cards: StateFlow<Map<String, StackHost>> = cardHosts.asStateFlow()

    /**
     * Hosts of interface slots by [studioSlotKey] while interfaces are on: the composer row of every pane and the
     * start window of a new chat.
     */
    val slots: StateFlow<Map<String, StackHost>> = slotHosts.asStateFlow()

    private val questionNavigation = ItemsNavigation<String>()

    // One child per session with open questions; a session without questions destroys its child and
    // host.
    private val questionItems = childItems(
        source = questionNavigation,
        serializer = null,
        initialItems = { Items() },
        key = "questions",
    ) { sessionId, context -> questionHost(context, sessionId) }

    private val questionHosts = MutableStateFlow<Map<String, StackHost>>(emptyMap())

    /**
     * Questionnaire hosts by session (toggle `questionnaire.enabled`): each session with open
     * questions gets its own nested host, shown inside the pane of that session, and loses it once
     * the session has no open questions.
     */
    val questions: StateFlow<Map<String, StackHost>> = questionHosts.asStateFlow()

    init {
        val checklistWatch = checklistItems.subscribe { children ->
            log.v { "Update inline checklist hosts: ${children.activeItems.size}" }
            checklistHosts.value = children.activeItems.mapValues { (_, child) -> child.first }
        }
        val checklistJob = scope.coroutineScope.launch {
            entries.checklistIds.collect { ids ->
                val shown = ids.toList()
                checklistNavigation.navigate { Items(shown, shown.associateWith { ActiveLifecycleState.RESUMED }) }
            }
        }
        lifecycle.doOnDestroy {
            checklistWatch.cancel()
            checklistJob.cancel()
        }

        val slotWatch = slotItems.subscribe { children ->
            log.v { "Update interface slot hosts: ${children.activeItems.size}" }
            slotHosts.value = children.activeItems.mapValues { (_, child) -> child.first }
        }
        val slotJob = scope.coroutineScope.launch {
            combine(surfacePanes.openPanes, surfaces.availability) { panes, isOn ->
                if (!isOn) {
                    emptyList()
                } else {
                    panes.flatMap { (paneId, pane) ->
                        listOfNotNull(
                            studioSlotKey(paneId, UiSlot.Composer),
                            studioSlotKey(paneId, UiSlot.Start).takeIf { pane.chatId == null },
                        )
                    }
                }
            }.distinctUntilChanged().collect { shown ->
                slotNavigation.navigate { Items(shown, shown.associateWith { ActiveLifecycleState.RESUMED }) }
            }
        }
        lifecycle.doOnDestroy {
            slotWatch.cancel()
            slotJob.cancel()
        }

        val cardWatch = cardItems.subscribe { children ->
            log.v { "Update interface card hosts: ${children.activeItems.size}" }
            cardHosts.value = children.activeItems.mapValues { (_, child) -> child.first }
        }
        val cardJob = scope.coroutineScope.launch {
            combine(
                surfacePanes.cardIds,
                surfaces.availability,
            ) { panes, isOn -> if (isOn) cardKeys(panes) else emptyList() }
                .distinctUntilChanged()
                .collect { shown ->
                    cardNavigation.navigate { Items(shown, shown.associateWith { ActiveLifecycleState.RESUMED }) }
                }
        }
        lifecycle.doOnDestroy {
            cardWatch.cancel()
            cardJob.cancel()
        }

        val attachments = scope.coroutineScope.launch {
            model.attachmentNavigation.collect { event ->
                when (event) {
                    is StudioAttachmentNavigation.Pick -> dialogs.navigator.navigateForResult(
                        AttachmentsPickRoute(event.requestId, event.support),
                        AttachmentsPicked,
                        NavOptions(transition = NavTransition.None),
                    )

                    is StudioAttachmentNavigation.Preview -> dialogs.navigator.navigate(
                        AttachmentPreviewRoute(
                            AttachmentId(event.id),
                            event.isExportRequested,
                        ),
                        NavOptions(transition = NavTransition.None),
                    )
                }
            }
        }
        val attachmentResults = scope.coroutineScope.launch {
            dialogs.navigator.results(AttachmentsPicked).collect { result ->
                model.store.intent(
                    AiStudioScreenIntent.AttachmentsSelected(
                        result.requestId,
                        result.attachments.map { it.toUi() }.toImmutableList(),
                    ),
                )
            }
        }

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
            attachments.cancel()
            attachmentResults.cancel()
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

    /** Whether the browser entry is offered in the active profile. */
    val showsBrowser: Flow<Boolean> = entries.showsBrowser

    /** Opens the browser inside the studio while preserving its sidebar. */
    fun openBrowser() {
        // An open browser behind the interface panel comes to the front; a hidden one opens and wins anyway.
        if (browserPresentation.value != BrowserPaneMode.Hidden) side.raiseBrowser()
        scope.coroutineScope.launch {
            machines.send(BrowserMachineKey, BrowserIntent.Public.Display(BrowserDisplay.Panel))
        }
    }

    /**
     * Returns the chat area to the studio's chat panes: every workspace entry above them (research) is
     * destroyed, so "back" from the chats never shows a closed research again.
     */
    fun closeChatArea() {
        log.i { "Close chat area" }
        workspaceNavigation.closeChatArea()
    }

    /** Whether the engine connection settings are offered. */
    val showsConnections: Flow<Boolean> = entries.showsConnections

    /** Whether the profile search settings are offered. */
    val showsProfileSettings: Flow<Boolean> = entries.showsProfileSettings

    /** Whether the one settings entry replaces the separate settings actions. */
    val showsUnifiedSettings: Flow<Boolean> = entries.showsUnifiedSettings

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    /** Opens the settings window above the studio; the studio keeps its state underneath. */
    fun openSettings() {
        log.i { "open settings" }
        navigator.navigate(
            SettingsRoute(),
            NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
        )
    }

    /** Opens the feature toggles panel above the studio; the studio keeps its state underneath. */
    fun openToggles() = navigator.navigate(
        TogglesPanelRoute(),
        NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
    )

    /** Opens profile-owned search provider settings. */
    fun openProfileSettings() {
        log.i { "open profile settings" }
        navigator.navigate(
            ProfileSettingsRoute(),
            NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
        )
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
        workspaceNavigation.openResearch(target)
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

/** Empty base route; rendering it never replaces the studio content. */
@Serializable
@SerialName("aistudio_no_dialog")
internal data object StudioNoDialogRoute : Route

internal data object StudioNoDialog : NavComponent

/** UI-only browser placement. */
enum class BrowserPaneMode {
    Hidden,
    Panel,
    Expanded,
}

/** Key of the interface slot [slot] of pane [paneId] in [AiStudioComponent.slots]. */
internal fun studioSlotKey(paneId: Int, slot: UiSlot): String = "$paneId.${slot.name.lowercase()}"

/** Key of the interface row above the input of pane [paneId]. */
internal fun studioComposerSlotKey(paneId: Int): String = studioSlotKey(paneId, UiSlot.Composer)

/** Key of the interfaces in the start window of the new chat of pane [paneId]. */
internal fun studioStartSlotKey(paneId: Int): String = studioSlotKey(paneId, UiSlot.Start)

private fun studioSlotOf(key: String): Pair<Int, UiSlot> {
    val (pane, slot) = key.split('.', limit = 2)
    return pane.toInt() to UiSlot.entries.first { it.name.lowercase() == slot }
}

private fun cardKeys(panes: Map<Int, Set<String>>): List<String> =
    panes.flatMap { (paneId, ids) -> ids.map { studioCardKey(paneId, it) } }.sorted()

/** Key of the card [cardId] in the reply feed of pane [paneId] in [AiStudioComponent.cards]. */
internal fun studioCardKey(paneId: Int, cardId: String): String = "$paneId:$cardId"

/** The cards of pane [paneId] among [cards], by card id. */
internal fun <T> paneCards(cards: Map<String, T>, paneId: Int): Map<String, T> = "$paneId:".let { prefix ->
    cards.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
}
