package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.ItemName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One call of a UI method: [arguments] from the surface (untrusted: built from user input and stored data) and where
 * the user acted — the chat [session] when there is one and its [workspace]. Never log any of them.
 */
public data class ScriptUiCall(
    val arguments: JsonObject,
    val session: SessionRef? = null,
    val workspace: WorkspaceRef? = null,
) {
    override fun toString(): String = "ScriptUiCall(***)"
}

/** What a UI method returns. Neither variant counts as a script failure; thrown exceptions do. */
public sealed interface ScriptUiResult {
    /** JSON the surface shows; at most HarnessLimits.UI_RESULT_BYTES when encoded. */
    public data class Value(val value: JsonElement) : ScriptUiResult {
        override fun toString(): String = "ScriptUiResult.Value(***)"
    }

    /** An expected problem the user reads, such as a missing login; at most HarnessLimits.UI_ERROR_CHARS. */
    public data class Error(val message: String) : ScriptUiResult {
        override fun toString(): String = "ScriptUiResult.Error(***)"
    }
}

/**
 * Methods that this harness's interfaces (the service-as-ui surface library) call for user actions, without an agent
 * turn. Approving the script covers these calls. A method runs on this activation for at most 60 seconds and is
 * cancelled when the user leaves the surface. Register methods while the script is evaluated.
 */
public interface ScriptUi {
    /**
     * Publishes the method [name] with a [description] for surface authors. At most HarnessLimits.UI_METHODS active
     * methods; a name can be registered again only after its previous registration is disposed.
     */
    public fun method(
        name: ItemName,
        description: String,
        handler: suspend (ScriptUiCall) -> ScriptUiResult,
    ): ScriptRegistration
}
