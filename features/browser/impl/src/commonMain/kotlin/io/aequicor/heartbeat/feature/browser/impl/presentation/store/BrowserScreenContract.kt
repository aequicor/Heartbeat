package io.aequicor.heartbeat.feature.browser.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.browser.api.BrowserZoomLevels
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserFaviconImage
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserShortcut
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

internal enum class BrowserPhase {
    Preparing,
    Disabled,
    Ready,
}

internal enum class BrowserScreenError {
    InvalidAddress,
    LoadFailed,
    UnsupportedAddress,
    EngineUnavailable,
}

/** UI projection plus the user's local address draft. */
@Immutable
internal data class BrowserScreenState(
    val phase: BrowserPhase = BrowserPhase.Preparing,
    val address: String = "",
    val isAddressEdited: Boolean = false,
    val url: String = "",
    val title: String = "",
    val zoomPercent: Int = 100,
    val isZoomEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val isBackAvailable: Boolean = false,
    val isForwardAvailable: Boolean = false,
    val error: BrowserScreenError? = null,
    val tabs: ImmutableList<BrowserTabUi> = persistentListOf(),
    val hasTabs: Boolean = false,
    val selectedTabId: String? = null,
    val isExpanded: Boolean = false,
    val favicons: ImmutableMap<String, BrowserFaviconImage> = persistentMapOf(),
    /** Addresses of tabs the user closed here, most recent last; [MAX_CLOSED_TABS] at most. */
    val closedTabUrls: ImmutableList<String> = persistentListOf(),
) : MVIState {
    val isZoomInAvailable: Boolean get() = zoomPercent < BrowserZoomLevels.last()
    val isZoomOutAvailable: Boolean get() = zoomPercent > BrowserZoomLevels.first()

    /** The tabs of the selected tab's group: the ones the strip shows. */
    val visibleTabs: List<BrowserTabUi>
        get() {
            val selected = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.firstOrNull()
            return tabs.filter { it.groupId == selected?.groupId }
        }
}

internal const val MAX_CLOSED_TABS = 25

internal data class BrowserTabUi(
    val id: String,
    val title: String,
    val isHumanControlled: Boolean,
    val hasAgent: Boolean,
    val controlRequestId: String? = null,
    val groupId: String? = null,
    val isLoading: Boolean = false,
    val faviconUrl: String = "",
    val isAwaitingResponse: Boolean = false,
    val url: String = "",
)

internal sealed interface BrowserScreenIntent : MVIIntent {
    data class AddressChanged(val value: String) : BrowserScreenIntent
    data object Open : BrowserScreenIntent

    data class ApproveControl(val tabId: String, val requestId: String) : BrowserScreenIntent

    data class RejectControl(val tabId: String, val requestId: String) : BrowserScreenIntent

    data object ClearData : BrowserScreenIntent

    data object NewTab : BrowserScreenIntent

    data class SelectTab(val id: String) : BrowserScreenIntent

    data class CloseTab(val id: String) : BrowserScreenIntent

    data object ToggleControl : BrowserScreenIntent

    data object ToggleExpanded : BrowserScreenIntent

    data class Shortcut(val shortcut: BrowserShortcut) : BrowserScreenIntent

    data object Hide : BrowserScreenIntent
    data object Back : BrowserScreenIntent
    data object Forward : BrowserScreenIntent
    data object Reload : BrowserScreenIntent
    data object Stop : BrowserScreenIntent
}

internal sealed interface BrowserScreenAction : MVIAction
