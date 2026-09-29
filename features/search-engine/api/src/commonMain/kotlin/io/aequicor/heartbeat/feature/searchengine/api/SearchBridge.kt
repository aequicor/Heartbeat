package io.aequicor.heartbeat.feature.searchengine.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures

/** Ephemeral profile-local endpoint for out-of-process adapters. The token is never a provider credential. */
public data class SearchBridgeEndpoint(public val origin: String, public val token: String)

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
}

/** A live [SearchBridge.attach] registration; [detach] is idempotent. */
public fun interface SearchBridgeAttachment {
    /** Removes the published native operations from the bridge. */
    public fun detach()
}
