package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The only spec format version so far. */
internal const val UI_SPEC_VERSION: Int = 1

/**
 * A declarative surface: initial local [state], [data] sources filled by owner methods, named [modals] and the root
 * [body]. Strings may hold `{{path}}` bindings (see [UiTemplate]); input `bind`, `set.key` and `call.into` name
 * state keys directly. [quickActions] are the contextual actions of a canonical `card` app that the host may draw
 * outside its body; the field is absent from specs that declare none, so older specs are read and stored unchanged.
 */
@Serializable
internal data class UiSpec(
    val version: Int,
    val title: String? = null,
    val state: Map<String, JsonElement> = emptyMap(),
    val data: Map<String, UiDataSource> = emptyMap(),
    val modals: Map<String, UiModal> = emptyMap(),
    val quickActions: List<UiQuickAction> = emptyList(),
    val body: UiNode,
) {
    override fun toString(): String =
        "UiSpec(version=$version, state=${state.size}, data=${data.size}, modals=${modals.size}, " +
            "quickActions=${quickActions.size})"
}

/**
 * One contextual action of an app, drawn by the host as a button next to the chat input. [id] is unique in the spec
 * and names the button in logs and in its busy state; [label] is required, since it names the button for screen
 * readers. [visibleWhen] hides the action while the binding is falsy, [enabledWhen] shows it disabled, [confirm]
 * is a question asked first. [action] may only call an owner method, reload a data source or set local state (alone
 * or in a `sequence`): actions that need the surface's own screen would otherwise replace the user's draft.
 */
@Serializable
internal data class UiQuickAction(
    val id: String,
    val label: String,
    val action: UiAction,
    val style: UiButtonStyle = UiButtonStyle.Secondary,
    val icon: String? = null,
    val visibleWhen: String? = null,
    val enabledWhen: String? = null,
    val confirm: String? = null,
)

/** Fills `data.<name>` with the result of the owner [method] called with [args] (strings may hold bindings). */
@Serializable
internal data class UiDataSource(
    val method: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val load: UiLoad = UiLoad.Auto,
)

/** Automatic sources run on opening and durable owner revisions; manual sources only through `reload`. */
@Serializable
internal enum class UiLoad {
    @SerialName("auto")
    Auto,

    @SerialName("manual")
    Manual,
}

/** A dialog the surface opens by name with the `open` action. */
@Serializable
internal data class UiModal(
    val title: String,
    val body: UiNode,
    @SerialName("wide") val isWide: Boolean = false,
)

/** Semantic color of text and badges, mapped to design-system tones. */
@Serializable
internal enum class UiTone {
    @SerialName("neutral")
    Neutral,

    @SerialName("brand")
    Brand,

    @SerialName("success")
    Success,

    @SerialName("warning")
    Warning,

    @SerialName("danger")
    Danger,
}

/** Typography role of a text node. */
@Serializable
internal enum class UiTextStyle {
    @SerialName("body")
    Body,

    @SerialName("caption")
    Caption,

    @SerialName("title")
    Title,

    @SerialName("heading")
    Heading,

    @SerialName("code")
    Code,
}

/** Spacing between children, mapped to design-system spacing tokens. */
@Serializable
internal enum class UiGap {
    @SerialName("none")
    None,

    @SerialName("small")
    Small,

    @SerialName("medium")
    Medium,

    @SerialName("large")
    Large,
}

/** Emphasis of a button. */
@Serializable
internal enum class UiButtonStyle {
    @SerialName("primary")
    Primary,

    @SerialName("secondary")
    Secondary,

    @SerialName("ghost")
    Ghost,

    @SerialName("danger")
    Danger,
}

/** A platform widget: date picker, or a file or folder chosen in a system dialog. */
@Serializable
internal enum class UiNativeWidget {
    @SerialName("date")
    Date,

    @SerialName("file")
    File,

    @SerialName("folder")
    Folder,
}

/** How `open` presents another surface. */
@Serializable
internal enum class UiPresentation {
    @SerialName("modal")
    Modal,

    @SerialName("panel")
    Panel,
}

/** Bounded viewport presets for an embedded page; the host maps them to its design-system dimensions. */
@Serializable
internal enum class UiWebHeight {
    @SerialName("compact")
    Compact,

    @SerialName("regular")
    Regular,

    @SerialName("tall")
    Tall,

    @SerialName("content")
    Content,
}
