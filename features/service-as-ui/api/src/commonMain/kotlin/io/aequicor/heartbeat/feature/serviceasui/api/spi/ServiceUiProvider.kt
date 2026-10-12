package io.aequicor.heartbeat.feature.serviceasui.api.spi

import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import kotlinx.coroutines.flow.Flow

/**
 * Source of surface owners and of the methods their surfaces call — for example harnesses, whose scripts publish
 * methods. Bound into a profile multibinding by the implementing feature; only implementers and
 * `platform-main:di-bundle` use this package (checked on review).
 *
 * Methods run for user actions in surfaces, never for agent turns, with the owner's own authority: approving the
 * owner's code covers them. The provider checks on every call that the owner exists, is enabled and is active in the
 * call context; surfaces of other owners cannot reach its methods.
 */
public interface ServiceUiProvider {
    /** Stable kind written into `UiOwner.kind` of stored surfaces; never renamed. */
    public val kind: String

    /** Owners that exist now. A complete snapshot without an owner deletes that owner's surfaces. */
    public val owners: Flow<UiOwners>

    /** Ids of owners active in [context]: connected to its chat, or preselected for a chat that is not created yet. */
    public fun activeOwners(context: UiCallContext): Flow<Set<String>>

    /** Methods [owner] publishes now. */
    public fun methods(owner: String): Flow<UiMethods>

    /**
     * Runs a method of [owner] for a user action. Main-safe; never throws except for cancellation, which also
     * cancels the method. Failures, including the owner's own errors, are results.
     */
    public suspend fun call(owner: String, call: UiMethodCall): UiMethodResult
}
