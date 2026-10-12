package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiFacts
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiValues
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.NativeWidgetUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiModalUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiPress
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** Why a method call produced no value. */
internal enum class FailureUi { Unavailable, NotFound, Inactive, TimedOut, Failed, TooLarge }

/** A message shown above the surface until the user dismisses it. */
@Immutable
internal sealed interface NoticeUi {
    /** The owner's own [message] for the user. */
    data class Message(val message: String) : NoticeUi

    /** A method call failed for [reason]. */
    data class Failed(val reason: FailureUi) : NoticeUi

    /** The action needs something this place or platform lacks (files on mobile, prompts without a chat). */
    data object Unsupported : NoticeUi

    /** A link that is not http(s). */
    data object InvalidLink : NoticeUi

    /** A file could not be saved, shown or picked. */
    data object FileFailed : NoticeUi

    /** The surface failed unexpectedly. */
    data object Broken : NoticeUi
}

/** A modal of the spec open over the surface with the arguments it was opened with. */
@Immutable
internal data class OpenModal(val name: String, val args: JsonObject) {
    override fun toString(): String = "OpenModal(name=$name)"
}

/**
 * The drawn surface: [root] and the top [modal] are evaluated from [values]; [isMethodsAvailable] tells whether the
 * owner's code runs here, [hasStarted] whether the automatic data sources were loaded.
 */
@Immutable
internal data class SurfaceState(
    val values: UiValues = UiValues(),
    val root: UiNodeUi? = null,
    val modals: ImmutableList<OpenModal> = persistentListOf(),
    val modal: UiModalUi? = null,
    val notice: NoticeUi? = null,
    val isMethodsAvailable: Boolean = false,
    val hasStarted: Boolean = false,
) : MVIState {
    override fun toString(): String =
        "SurfaceState(modals=${modals.size}, notice=${notice?.let { it::class.simpleName } ?: "none"}, " +
            "isMethodsAvailable=$isMethodsAvailable)"
}

/** User input of a surface. */
internal sealed interface SurfaceIntent : MVIIntent {
    /** A button or a row was pressed (after its confirmation, if any). */
    data class Press(val press: UiPress) : SurfaceIntent

    /** An input changed the state key [key]. */
    data class Input(val key: String, val value: JsonElement) : SurfaceIntent {
        override fun toString(): String = "Input(key=$key)"
    }

    /** A link inside drawn markdown was clicked; it is always confirmed, since the text may come from anyone. */
    data class OpenLink(val url: String) : SurfaceIntent {
        override fun toString(): String = "OpenLink"
    }

    /** A native widget asks for a system dialog writing the state key [key]. */
    data class Pick(val key: String, val widget: NativeWidgetUi, val extensions: List<String>) : SurfaceIntent

    /** Loads the data source [source] again, or every source when null. */
    data class Reload(val source: String? = null) : SurfaceIntent

    /** Closes the top modal. */
    data object DismissModal : SurfaceIntent

    /** Hides the notice. */
    data object DismissNotice : SurfaceIntent
}

/** One-shot effects the screen performs. */
internal sealed interface SurfaceAction : MVIAction {
    /** Opens [url]; asks the user first unless [isTrusted]. */
    data class OpenUrl(val url: String, val isTrusted: Boolean) : SurfaceAction {
        override fun toString(): String = "OpenUrl(isTrusted=$isTrusted)"
    }

    /** Copies [text] to the clipboard. */
    data class Copy(val text: String) : SurfaceAction {
        override fun toString(): String = "Copy(***)"
    }

    /** Opens the owner's surface [name] as a modal over this one. */
    data class OpenSurface(val name: String, val args: JsonObject) : SurfaceAction {
        override fun toString(): String = "OpenSurface(name=$name)"
    }
}

/** The host side of a surface: the place it is drawn in. */
internal interface SurfaceHost {
    /** Puts [text] into the host's composer, or sends it when [isSubmitted]; false when no host can take it. */
    suspend fun prompt(text: String, isSubmitted: Boolean): Boolean

    /** Opens the owner's surface [name] in the host's dock with [args]; false when it cannot. */
    suspend fun openPanel(name: String, args: JsonObject): Boolean
}

/**
 * What a surface store works with: the decoded [spec], its [owner] (null for a card without one, which cannot call
 * methods), the call [context] of the place, the [args] it was opened with and the [host].
 */
internal data class SurfaceSession(
    val spec: UiSpec,
    val owner: UiOwner?,
    val context: UiCallContext,
    val args: JsonObject,
    val facts: UiFacts,
    val host: SurfaceHost,
) {
    override fun toString(): String = "SurfaceSession(owner=${owner?.id ?: "none"})"
}
