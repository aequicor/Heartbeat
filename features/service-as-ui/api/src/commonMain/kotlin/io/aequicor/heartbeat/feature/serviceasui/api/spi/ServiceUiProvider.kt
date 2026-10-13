package io.aequicor.heartbeat.feature.serviceasui.api.spi

import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodReview
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Source of surface owners and of the methods their surfaces call — for example harnesses, whose scripts publish
 * methods. Bound into a profile multibinding by the implementing feature; only implementers and
 * `platform-main:di-bundle` use this package (checked on review).
 *
 * Methods run for surface actions with the owner's authority. Agent calls additionally require independent review
 * of [review] and a matching [UiMethodCall.expectedBinding]. The provider checks on every call that the owner exists,
 * is enabled and is active in the call context; surfaces of other owners cannot reach its methods.
 */
public interface ServiceUiProvider {
    /** Stable kind written into `UiOwner.kind` of stored surfaces; never renamed. */
    public val kind: String

    /** Owners that exist now. A complete snapshot without an owner deletes that owner's surfaces. */
    public val owners: Flow<UiOwners>

    /**
     * Ids of owners active in [context]: connected to its chat, or preselected for a chat that is not created yet.
     * Emits the current set at once: places combine every provider and show nothing until each one has reported.
     */
    public fun activeOwners(context: UiCallContext): Flow<Set<String>>

    /**
     * Revision of durable data owned by [owner], emitted immediately and after each successful change. Reads,
     * failed writes and unchanged values must not advance it: automatic sources reload when it changes. The
     * revision only needs to be monotonic for this profile runtime; opening a surface always reads fresh data.
     * Implementations without durable data emit the constant zero.
     */
    public fun dataRevision(owner: String): Flow<Long> = flowOf(0L)

    /** Methods [owner] publishes now, including a new [UiMethods.generation] when its implementation changes. */
    public fun methods(owner: String): Flow<UiMethods>

    /**
     * Reads the exact live implementation selected for [method], without invoking author code. Returns null when
     * unavailable, inactive in [context], or not reviewable. Main-safe; only cancellation may throw. Providers that
     * support this snapshot must enforce its binding in [call] after all waits, immediately before author code.
     */
    public suspend fun review(owner: String, method: String, context: UiCallContext): UiMethodReview? = null

    /**
     * Runs a method of [owner] for a surface action or independently reviewed agent call. A non-null expected
     * binding must match the current selected registration; unsupported or stale bindings return Unavailable.
     * Main-safe; never throws except cancellation, which also cancels the method. Owner failures are results.
     */
    public suspend fun call(owner: String, call: UiMethodCall): UiMethodResult
}
