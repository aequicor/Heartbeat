package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import com.arkivanov.essenty.backhandler.BackCallback
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the right side of the studio shows. */
enum class SidePanelUi { None, Browser, Interface }

/** The side panel, the browser mode and the interface panel sequence it was chosen from. */
internal data class SideChoice(
    val shown: SidePanelUi = SidePanelUi.None,
    val browser: BrowserPaneMode = BrowserPaneMode.Hidden,
    val panel: Long? = null,
)

/**
 * The next side panel: the one opened last wins; when the shown one closes, the other one shows if it is still
 * open. A new interface panel (a new opening [panel] sequence) wins over the browser opened at the same moment.
 */
internal fun SideChoice.next(browser: BrowserPaneMode, panel: Long?): SideChoice {
    val isBrowserOpen = browser != BrowserPaneMode.Hidden
    val wanted = when {
        panel != null && panel != this.panel -> SidePanelUi.Interface
        isBrowserOpen && this.browser == BrowserPaneMode.Hidden -> SidePanelUi.Browser
        else -> shown
    }
    val shown = when {
        wanted == SidePanelUi.Interface && panel != null -> SidePanelUi.Interface
        wanted == SidePanelUi.Browser && isBrowserOpen -> SidePanelUi.Browser
        panel != null -> SidePanelUi.Interface
        isBrowserOpen -> SidePanelUi.Browser
        else -> SidePanelUi.None
    }
    return SideChoice(shown, browser, panel)
}

/** Chooses between the browser and the interface panel of the window; "back" closes a shown interface panel. */
internal class StudioSidePanel(browser: StateFlow<BrowserPaneMode>, surfaces: StudioSurfaces, scope: CoroutineScope) {
    private val log = Log.tag("AiStudioComponent")

    /** Registered after the browser's: a shown interface panel handles "back" first. */
    val back: BackCallback = BackCallback(isEnabled = false) {
        scope.launch { surfaces.closePanel() }
    }

    /** The side panel shown now. */
    val shown: StateFlow<SidePanelUi> = combine(browser, surfaces.panel) { mode, panel -> mode to panel }
        .runningFold(SideChoice()) { choice, (mode, panel) -> choice.next(mode, panel) }
        .onEach { choice ->
            back.isEnabled = choice.shown == SidePanelUi.Interface
            log.v { "Side panel ${choice.shown}" }
        }
        .map { it.shown }
        .stateIn(scope, SharingStarted.Eagerly, SidePanelUi.None)
}
