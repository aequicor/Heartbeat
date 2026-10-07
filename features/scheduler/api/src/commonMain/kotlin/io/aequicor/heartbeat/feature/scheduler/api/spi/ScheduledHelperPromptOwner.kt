package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadata
import kotlinx.coroutines.flow.Flow

/** Exact durable helper preparation, created by its host after opening and before any native prompt.
 * Workspace is the active native route checkout, never a requested or script-supplied project. */
public data class HelperPromptAttempt(
    val helper: HelperMetadata,
    val session: SessionRef,
    val request: RequestId,
    val handoff: HelperHandoff?,
    val workspace: WorkspaceRef?,
) {
    init {
        require(helper.session == session && helper.lastRequest == request) { "Helper preparation identity mismatch" }
    }

    override fun toString(): String = "HelperPromptAttempt(***)"
}

/**
 * Profile contribution for durable context transfer and live admission before a helper prompt. Exactly one owner
 * must match an explicit handoff feature; every opted-in initiator observer also participates. Missing/ambiguous
 * ownership, failed IO and unavailable initial decisions refuse submission. False is sticky for one attempt.
 * The host collects again after journaling Submitting and immediately before entering the native sender.
 * Implementations must finish durable ancestry and attachment writes before emitting true, then recheck authority.
 * This is host coordination, not a session hook; implementations must not derive authority from prompt text.
 */
public interface ScheduledHelperPromptOwner {
    /** Namespace matching HelperHandoff.ownerFeature; reading this must not start feature work. */
    public val feature: String

    /** Observes exact initiating requests independently of explicit ownership and feature activation. */
    public val isObservingInitiators: Boolean get() = false

    /** Current decision followed by live changes. Repeated collection must be idempotent for the exact target. */
    public fun admission(attempt: HelperPromptAttempt): Flow<Boolean>
}
