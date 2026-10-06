package io.aequicor.heartbeat.feature.harness.api.event

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlin.time.Instant

/**
 * Lifecycle of opted-in handles where this harness is active. The host supplies [context] and deduplicates
 * owner/turn notifications; an event cannot grant access to a session after the harness is detached.
 */
public sealed class SessionEvent : HarnessEvent() {
    /** Trusted session, owner and request/turn correlation inherited from facade observation. */
    public abstract val context: SessionHookContext

    /** One opted-in facade handle opened. */
    public data class Opened(override val context: SessionHookContext, override val at: Instant) : SessionEvent()

    /** A turn was accepted by this handle's owner. */
    public data class TurnStarted(override val context: SessionHookContext, override val at: Instant) : SessionEvent()

    /** An accepted turn ended; its result still requires the authoritative history/helper contract. */
    public data class TurnFinished(
        override val context: SessionHookContext,
        val outcome: TurnOutcome,
        override val at: Instant,
    ) : SessionEvent()

    /** A new pending permission on this owner's turn; replay is not proof that it still awaits an answer. */
    public data class PermissionRequested(
        override val context: SessionHookContext,
        val permission: PermissionRequest,
        override val at: Instant,
    ) : SessionEvent()

    /** One facade handle closed; other owners and accepted native work may remain active. */
    public data class Closed(override val context: SessionHookContext, override val at: Instant) : SessionEvent()
}
