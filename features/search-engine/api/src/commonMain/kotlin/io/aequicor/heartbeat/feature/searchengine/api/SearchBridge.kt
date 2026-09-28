package io.aequicor.heartbeat.feature.searchengine.api

/** Ephemeral profile-local endpoint for out-of-process adapters. The token is never a provider credential. */
public data class SearchBridgeEndpoint(public val origin: String, public val token: String)

/** Bridge used only by adapters that run in separate processes. */
public interface SearchBridge {
    /** Returns the loopback origin and an opaque profile-lifetime access token. */
    public fun endpoint(): SearchBridgeEndpoint
}
