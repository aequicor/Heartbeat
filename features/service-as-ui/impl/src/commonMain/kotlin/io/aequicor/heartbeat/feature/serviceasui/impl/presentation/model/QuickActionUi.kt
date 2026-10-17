package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model

import androidx.compose.runtime.Immutable

/**
 * A resolved quick action of an app: a button the host draws next to the chat input. [id] is the spec's identity of
 * the action, stable across evaluations; [isBusy] is true while its [press] runs, and [confirm] is a question to ask
 * first. Pressing sends [press] back to the store of the app that declared the action.
 */
@Immutable
internal data class QuickActionUi(
    val id: String,
    val label: String,
    val style: ButtonStyleUi,
    val icon: String?,
    val isEnabled: Boolean,
    val isBusy: Boolean,
    val confirm: String?,
    val press: UiPress,
)

/** The key under which the store tracks the busy state of quick action [id]; body buttons never use it. */
internal fun quickActionOrigin(id: String): String = "q.$id"
