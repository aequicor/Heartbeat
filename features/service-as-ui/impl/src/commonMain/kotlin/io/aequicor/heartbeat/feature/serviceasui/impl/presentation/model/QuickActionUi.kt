package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiScope

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

/** A quick action with a harmless press (`close`) that only has to be drawn, in previews and in tests. */
internal fun inertQuickAction(
    id: String,
    label: String,
    style: ButtonStyleUi = ButtonStyleUi.Secondary,
    icon: String? = null,
    isEnabled: Boolean = true,
    isBusy: Boolean = false,
    confirm: String? = null,
): QuickActionUi {
    val press = UiPress(quickActionOrigin(id), UiAction.Close, UiScope())
    return QuickActionUi(id, label, style, icon, isEnabled, isBusy, confirm, press)
}
