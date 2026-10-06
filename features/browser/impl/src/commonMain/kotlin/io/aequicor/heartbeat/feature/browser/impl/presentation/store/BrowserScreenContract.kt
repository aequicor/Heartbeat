package io.aequicor.heartbeat.feature.browser.impl.presentation.store

import androidx.compose.runtime.Immutable
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

internal enum class BrowserPhase { Preparing, Disabled, Ready }

internal enum class BrowserScreenError { InvalidAddress, LoadFailed, UnsupportedAddress, EngineUnavailable }

/** UI projection plus the user's local address draft. */
@Immutable
internal data class BrowserScreenState(
    val phase: BrowserPhase = BrowserPhase.Preparing,
    val address: String = "",
    val isAddressEdited: Boolean = false,
    val url: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isBackAvailable: Boolean = false,
    val isForwardAvailable: Boolean = false,
    val error: BrowserScreenError? = null,
) : MVIState

internal sealed interface BrowserScreenIntent : MVIIntent {
    data class AddressChanged(val value: String) : BrowserScreenIntent
    data object Open : BrowserScreenIntent
    data object Back : BrowserScreenIntent
    data object Forward : BrowserScreenIntent
    data object Reload : BrowserScreenIntent
    data object Stop : BrowserScreenIntent
}

internal sealed interface BrowserScreenAction : MVIAction
