package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Methods and activity of surface owners, reached through their providers. */
internal interface UiMethodCalls {
    /** Owners of every provider that are active in [context]; emits once every provider has reported its owners. */
    fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>>

    /** Methods [owner] publishes now; unavailable when no provider knows its kind. */
    fun methods(owner: UiOwner): Flow<UiMethods>

    /**
     * Calls [method] of [owner] for a user action in [context]. Never throws except for cancellation, which also
     * cancels the method; arguments and results beyond the limits are refused.
     */
    suspend fun call(owner: UiOwner, method: String, arguments: JsonObject, context: UiCallContext): UiMethodResult

    /**
     * [call] made by the script of a web page rather than a user action: the page decides how often it calls, so it is
     * logged only at trace level.
     */
    suspend fun callFromPage(
        owner: UiOwner,
        method: String,
        arguments: JsonObject,
        context: UiCallContext,
    ): UiMethodResult = call(owner, method, arguments, context)
}

/** How a platform operation ended. */
internal sealed interface UiPlatformResult {
    /** Done; a picker returns the chosen [path], which never reaches logs. */
    data class Done(val path: String? = null) : UiPlatformResult {
        override fun toString(): String = "Done"
    }

    /** The user closed the system dialog. */
    data object Cancelled : UiPlatformResult

    /** This platform has no such operation (files on mobile). */
    data object Unsupported : UiPlatformResult

    /** The operation failed; the platform logged why. */
    data object Failed : UiPlatformResult
}

/** Platform operations of surfaces; each is [UiPlatformResult.Unsupported] where the platform lacks it. */
internal interface UiPlatform {
    /** Saves [text] to a file the user picks, suggesting [name] and [extensions]. */
    suspend fun save(text: String, name: String, extensions: List<String>): UiPlatformResult

    /** Shows the existing absolute [path] in the system file manager without opening it. */
    suspend fun reveal(path: String): UiPlatformResult

    /** A folder ([isFolder]) or a file with one of [extensions] (any when empty) the user picks. */
    suspend fun pick(isFolder: Boolean, extensions: List<String>): UiPlatformResult
}
