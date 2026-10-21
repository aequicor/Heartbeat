package io.aequicor.heartbeat.feature.searchengine.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import kotlinx.serialization.json.JsonObject

/** Ephemeral profile-local endpoint for out-of-process adapters. The token is never a provider credential. */
public data class SearchBridgeEndpoint(public val origin: String, public val token: String) {
    override fun toString(): String = "SearchBridgeEndpoint(***)"
}

/** Bridge used only by adapters that run in separate processes. */
public interface SearchBridge {
    /** Returns the loopback origin and an opaque profile-lifetime access token. */
    public fun endpoint(): SearchBridgeEndpoint

    /**
     * Publishes native web operations of a live engine for the bridge tools. While the attachment is live,
     * routed tools prefer these operations over the configured provider; [SearchBridgeAttachment.detach]
     * removes them again. Attaching is cheap and never starts IO.
     */
    public fun attach(features: EngineFeatures): SearchBridgeAttachment

    /**
     * Creates a separate bearer bound to [guard], with no access to profile-wide native feature attachments.
     * Unsupported bridges fail closed; callers requiring scoped authorization must never fall back to [endpoint].
     * Closing the access revokes its token and cancels its pending requests, including an awaited approval.
     */
    public fun attach(guard: SearchBridgeGuard): SearchBridgeAccess =
        throw UnsupportedOperationException("Scoped search authorization is unavailable")
}

/** A host-created authorization boundary, never supplied by a bridge request or bearer claim. */
public fun interface SearchBridgeGuard {
    /**
     * Checks the exact full [arguments] in the captured turn before invoking [operation], then observes its result.
     * The operation is one-shot and remains subject to the access lifetime. Recheck the captured turn after
     * authorization and propagate cancellation; a profile token alone is never user authorization.
     */
    public suspend fun execute(
        name: String,
        arguments: JsonObject,
        operation: suspend () -> AgentToolResult,
    ): AgentToolResult
}

/** Scoped search endpoint; closing it is idempotent and prevents both pending and future execution. */
public interface SearchBridgeAccess : AutoCloseable {
    /** Unique capability for this attachment, never the profile-wide bearer. */
    public val endpoint: SearchBridgeEndpoint
}

/** A live [SearchBridge.attach] registration; [detach] is idempotent. */
public fun interface SearchBridgeAttachment {
    /** Removes the published native operations from the bridge. */
    public fun detach()
}
