package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.items.Items
import com.arkivanov.decompose.router.items.Items.ActiveLifecycleState
import com.arkivanov.decompose.router.items.ItemsNavigation
import com.arkivanov.decompose.router.items.childItems
import com.arkivanov.decompose.router.items.navigate
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiPanelRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the right side of the studio shows. */
enum class SidePanelUi { None, Browser, Interface, File }

/**
 * The side panel, the browser mode, the interface panel sequence, the browser raise and the file preview opening it
 * was chosen from.
 */
internal data class SideChoice(
    val shown: SidePanelUi = SidePanelUi.None,
    val browser: BrowserPaneMode = BrowserPaneMode.Hidden,
    val panel: Long? = null,
    val raise: Long = 0,
    val file: Long? = null,
)

/**
 * The next side panel: the one opened last wins; when the shown one closes, another one shows if it is still
 * open. The user raising an open browser ([raise] grows) wins over everything; a newly opened file (a new [file]
 * opening) wins over a new interface panel, which wins over the browser opened at the same moment.
 */
internal fun SideChoice.next(
    browser: BrowserPaneMode,
    panel: Long?,
    raise: Long = this.raise,
    file: Long? = this.file,
): SideChoice {
    val isBrowserOpen = browser != BrowserPaneMode.Hidden
    val wanted = when {
        raise != this.raise && isBrowserOpen -> SidePanelUi.Browser
        file != null && file != this.file -> SidePanelUi.File
        panel != null && panel != this.panel -> SidePanelUi.Interface
        isBrowserOpen && this.browser == BrowserPaneMode.Hidden -> SidePanelUi.Browser
        else -> shown
    }
    val open = OpenPanels(isBrowserOpen, isPanelOpen = panel != null, isFileOpen = file != null)
    return SideChoice(open.shown(wanted), browser, panel, raise, file)
}

private data class OpenPanels(val isBrowserOpen: Boolean, val isPanelOpen: Boolean, val isFileOpen: Boolean) {
    /** [wanted] while it is open, else another panel that is open, else nothing. */
    fun shown(wanted: SidePanelUi): SidePanelUi = when {
        wanted == SidePanelUi.File && isFileOpen -> SidePanelUi.File
        wanted == SidePanelUi.Interface && isPanelOpen -> SidePanelUi.Interface
        wanted == SidePanelUi.Browser && isBrowserOpen -> SidePanelUi.Browser
        isPanelOpen -> SidePanelUi.Interface
        isBrowserOpen -> SidePanelUi.Browser
        isFileOpen -> SidePanelUi.File
        else -> SidePanelUi.None
    }
}

/**
 * The side of a studio window: chooses between the browser and the interface panel of the window's dock, hosts that
 * panel while interfaces are on, and closes a shown interface panel on "back".
 */
@OptIn(ExperimentalDecomposeApi::class) // childItems hosts the panel only while interfaces are on.
internal class StudioSidePanel(
    context: ComponentContext,
    navigator: Navigator,
    hosts: NavHostFactory,
    browser: StateFlow<BrowserPaneMode>,
    surfaces: StudioSurfaces,
    scope: CoroutineScope,
    files: StateFlow<Long?> = MutableStateFlow(null),
    closeFile: () -> Unit = {},
) {
    private val log = Log.tag("StudioSidePanel")
    private val raises = MutableStateFlow(0L)
    private val navigation = ItemsNavigation<String>()
    private val items = context.childItems(
        source = navigation,
        serializer = null,
        initialItems = { Items() },
        key = "interface-panel",
    ) { _, child ->
        hosts.stack(
            context = child,
            parent = navigator,
            name = "interface-panel",
            initial = listOf(ServiceUiPanelRoute(surfaces.dock)),
            local = emptyList(),
            global = GlobalRoutes.Only(setOf(ServiceUiPanelRoute::class)),
        )
    }
    private val host = MutableStateFlow<StackHost?>(null)

    /** The host of the interface panel while interfaces are on. */
    val panel: StateFlow<StackHost?> = host.asStateFlow()

    private var shownNow = SidePanelUi.None

    /** Registered after the browser's: a shown interface panel or file preview handles "back" first. */
    val back: BackCallback = BackCallback(isEnabled = false) {
        if (shownNow == SidePanelUi.File) closeFile() else scope.launch { surfaces.closePanel() }
    }

    /** The side panel shown now. */
    val shown: StateFlow<SidePanelUi> = combine(browser, surfaces.panel, raises, files) { mode, panel, raise, file ->
        Inputs(mode, panel, raise, file)
    }
        .runningFold(SideChoice()) { choice, input -> choice.next(input.mode, input.panel, input.raise, input.file) }
        .onEach { choice ->
            shownNow = choice.shown
            back.isEnabled = choice.shown == SidePanelUi.Interface || choice.shown == SidePanelUi.File
            log.v { "Side panel ${choice.shown}" }
        }
        .map { it.shown }
        .stateIn(scope, SharingStarted.Eagerly, SidePanelUi.None)

    init {
        val watch = items.subscribe { children ->
            val next = children.activeItems.values.firstOrNull()?.first
            log.v { "Interface panel host ${if (next == null) "closed" else "open"}" }
            host.value = next
        }
        val job = scope.launch {
            surfaces.availability.distinctUntilChanged().collect { isOn ->
                val shown = if (isOn) listOf(PANEL) else emptyList()
                navigation.navigate { Items(shown, shown.associateWith { ActiveLifecycleState.RESUMED }) }
            }
        }
        context.lifecycle.doOnDestroy {
            watch.cancel()
            job.cancel()
        }
    }

    /** Brings the open browser in front of the interface panel. */
    fun raiseBrowser() {
        log.i { "Raise the browser over the interface panel" }
        raises.value += 1
    }

    private data class Inputs(val mode: BrowserPaneMode, val panel: Long?, val raise: Long, val file: Long?)

    private companion object {
        const val PANEL = "panel"
    }
}
