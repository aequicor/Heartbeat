package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What a button, a row click or a step of a sequence does. */
@Serializable
internal sealed interface UiAction {
    /** Calls the owner [method] with [args] (strings may hold bindings), storing the result in state key [into]. */
    @Serializable
    @SerialName("call")
    data class Call(val method: String, val args: JsonObject = JsonObject(emptyMap()), val into: String? = null) :
        UiAction

    /** Runs the data source [source] again. */
    @Serializable
    @SerialName("reload")
    data class Reload(val source: String) : UiAction

    /** Reveals the latest card of the owner's [surface] in the current chat without opening another panel. */
    @Serializable
    @SerialName("focusCard")
    data class FocusCard(val surface: String) : UiAction

    /** Writes [value] (strings may hold bindings) into state key [key]. */
    @Serializable
    @SerialName("set")
    data class SetState(val key: String, val value: JsonElement) : UiAction

    /** Opens the named [modal] of this surface, or the owner's other [surface] presented as [presentation]. */
    @Serializable
    @SerialName("open")
    data class Open(
        val modal: String? = null,
        val surface: String? = null,
        val presentation: UiPresentation = UiPresentation.Modal,
        val args: JsonObject = JsonObject(emptyMap()),
    ) : UiAction

    /** Closes the modal the action runs in. */
    @Serializable
    @SerialName("close")
    data object Close : UiAction

    /** Puts [text] into the chat input, or sends it to the agent when [isSubmitted]. */
    @Serializable
    @SerialName("prompt")
    data class Prompt(
        val text: String,
        @SerialName("submit") val isSubmitted: Boolean = false,
    ) : UiAction

    /** Opens an http(s) [url] in the browser. */
    @Serializable
    @SerialName("openUrl")
    data class OpenUrl(val url: String) : UiAction

    /** Copies [text] to the clipboard. */
    @Serializable
    @SerialName("copy")
    data class Copy(val text: String) : UiAction

    /** Saves [text] to a file the user picks, suggesting [name]. */
    @Serializable
    @SerialName("saveFile")
    data class SaveFile(
        val text: String,
        val name: String = "export.txt",
        val extensions: List<String> = emptyList(),
    ) : UiAction

    /** Shows the absolute [path] in the system file manager. */
    @Serializable
    @SerialName("reveal")
    data class Reveal(val path: String) : UiAction

    /** Runs [actions] in order, stopping at the first that fails. */
    @Serializable
    @SerialName("sequence")
    data class Steps(val actions: List<UiAction>) : UiAction
}
