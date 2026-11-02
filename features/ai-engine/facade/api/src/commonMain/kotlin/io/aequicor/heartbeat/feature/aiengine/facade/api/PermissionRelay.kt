package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/**
 * Exact host-issued permission appearance. This is evidence to validate, never authority by itself. The canonical
 * execution provider must recognize and consume its nonce once; decoded or caller-created copies grant no rights.
 * Restart, handle replacement or a changed pending operation requires a newly issued appearance.
 */
@Serializable
public data class PermissionRelay(
    val binding: SessionExecutionBinding,
    val owner: LogicalSessionId,
    val session: SessionRef,
    val target: EngineTarget,
    val request: RequestId,
    val permission: PermissionRequest,
    val nonce: String,
    val revision: Long,
) {
    init {
        require(binding.logicalSession?.owner == owner) { "Relay requires the exact full conversation owner" }
        require(session.engine == target.engine && nonce.isNotBlank() && revision >= 0)
    }

    /** Only this operation may be approved; a relay never creates a persistent trust grant. */
    public fun accepts(decision: PermissionDecision): Boolean = permission.accepts(decision) &&
        permission.options.any {
            it.id == decision.option && (it.isSkip || it.isOneTime)
        }
}

/** Host-only transport for an executor answer through its canonical owner; it never submits human instructions. */
public interface RelaysPermissions : EngineFeature {
    /** Enqueues the exact decision; the provider consumes [relay] again at the native handoff boundary. */
    public suspend fun respond(relay: PermissionRelay, decision: PermissionDecision)

    /** Facade capability, exposed only when the native session supports permission responses. */
    public companion object : EngineFeatureKey<RelaysPermissions>(
        EngineFeatureId("session.relay_permission"),
        RelaysPermissions::class,
    )
}
