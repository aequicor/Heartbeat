package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Methods of surface owners, reached through their providers. */
internal interface UiMethodCalls {
    /** Methods [owner] publishes now; unavailable when no provider knows its kind. */
    fun methods(owner: UiOwner): Flow<UiMethods>

    /**
     * Calls [method] of [owner] for a user action in [context]. Never throws except for cancellation, which also
     * cancels the method; arguments and results beyond the limits are refused.
     */
    suspend fun call(owner: UiOwner, method: String, arguments: JsonObject, context: UiCallContext): UiMethodResult
}

/** Operations of the platform a surface asks for; every one is refused where the platform lacks it. */
internal interface UiPlatform {
    /** Whether file and folder pickers, saving and revealing files exist here (desktop). */
    val hasFiles: Boolean

    /** Saves [text] to a file the user picks, suggesting [name]; false when the user cancels or it fails. */
    suspend fun save(text: String, name: String, extensions: List<String>): Boolean

    /** Shows the existing absolute [path] in the system file manager; false when it cannot. */
    suspend fun reveal(path: String): Boolean

    /** A file the user picks, or null when cancelled. */
    suspend fun pickFile(extensions: List<String>): String?

    /** A folder the user picks, or null when cancelled. */
    suspend fun pickFolder(): String?
}
