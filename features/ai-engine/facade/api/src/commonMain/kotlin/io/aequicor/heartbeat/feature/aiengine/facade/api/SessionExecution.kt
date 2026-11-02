package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** Host-owned incarnation, independent of native handles, attempts and reusable display identities. */
@Serializable
public data class SessionExecutionInstance(val provider: String, val id: String) {
    init {
        require(provider.isNotBlank() && id.isNotBlank()) { "Execution authority needs a provider and incarnation" }
    }
}

/**
 * Immutable host authority of a native executor. Null [logicalSession] denotes internal execution without a proven
 * full conversation owner. Binding metadata never grants liveness: the owning host must still authorize it.
 */
@Serializable
public data class SessionExecutionBinding(
    val instance: SessionExecutionInstance,
    val logicalSession: LogicalSessionContext? = null,
) {
    init {
        require(logicalSession == null || !logicalSession.isHumanInteractive) {
            "Executor binding cannot restrict an independent full conversation"
        }
    }
}

/**
 * Profile contribution of the canonical lifecycle owner. Missing, unloaded, unreadable or terminated authority
 * must deny admission. Implementations compare the full binding, including the incarnation and logical owner;
 * native ancestry, labels and current preferences cannot establish authority.
 */
public interface SessionExecutionAuthority {
    /** Stable host namespace used by [SessionExecutionInstance.provider]. */
    public val provider: String

    /** Whether durable and current host state both authorize this exact instance. Errors fail closed. */
    public suspend fun permits(binding: SessionExecutionBinding): Boolean

    /** Checks the original host payload before hooks may prepare it; does not consume submission authority. */
    public suspend fun permitsPrompt(
        binding: SessionExecutionBinding,
        session: SessionRef,
        target: EngineTarget,
        request: PromptRequest,
    ): Boolean = permits(binding)

    /**
     * Claims this request immediately before native send. Hosts with durable attempts atomically consume their
     * one-shot entry here. A successful claim is not native acceptance; failure or cancellation never permits replay.
     * Invoked under the instance admission gate; implementations must not call that gate recursively.
     */
    public suspend fun enterPrompt(
        binding: SessionExecutionBinding,
        session: SessionRef,
        request: RequestId,
    ): Boolean = permits(binding)
}

/** Shared admission barrier used by lifecycle owners when durably retiring an executor instance. */
public interface SessionExecutionControl {
    /**
     * Denies new admissions before [persist] records termination in the canonical host journal. Persistence failure
     * leaves the in-memory barrier closed; retry must still persist before reporting durable termination.
     * The callback must not perform native RPC or call this barrier recursively.
     */
    public suspend fun fence(binding: SessionExecutionBinding, persist: suspend () -> Unit)

    /**
     * Waits for every operation admitted before the fence to finish its handoff. Requires a prior successful fence.
     * This is NOT native terminal evidence: the caller must inspect and stop the exact resulting work separately.
     * Cancellation or a timeout leaves the fence closed and the outstanding handoffs tracked.
     */
    public suspend fun awaitAdmissions(binding: SessionExecutionBinding)

    /**
     * Temporarily denies admission and drains existing handoffs while [block] inspects or stops exact work.
     * The barrier survives cancellation until [block] unwinds; it does not permanently retire the identity.
     * Unsupported implementations fail closed. A drained handoff alone is not native terminal evidence.
     */
    public suspend fun <T> suspended(binding: SessionExecutionBinding, block: suspend () -> T): T =
        error("Temporary execution suspension is unsupported")

    /**
     * Known native segments for privileged inspection/Stop, including late refs whose first binding write failed.
     * An empty set does not settle an unresolved native creation or prove termination.
     */
    public suspend fun references(binding: SessionExecutionBinding): Set<SessionRef>
}
