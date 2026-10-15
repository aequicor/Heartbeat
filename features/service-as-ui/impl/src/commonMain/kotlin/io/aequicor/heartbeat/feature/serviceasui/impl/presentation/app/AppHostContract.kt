package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import androidx.compose.runtime.Immutable
import pro.respawn.flowmvi.api.MVIIntent

/** Host-native app chrome; the full retained model is addressed by [key], never mounted by a compact entry. */
@Immutable
internal data class AppHostState(
    val title: String = "",
    val description: String = "",
    val key: String? = null,
    val isStart: Boolean = false,
    val isExpanded: Boolean = false,
    val sequence: Long = 0,
    val isLoading: Boolean = false,
)

/** Native app chrome actions, separate from application methods. */
internal sealed interface AppHostIntent : MVIIntent {
    data object Open : AppHostIntent
    data class Close(val sequence: Long) : AppHostIntent
}
