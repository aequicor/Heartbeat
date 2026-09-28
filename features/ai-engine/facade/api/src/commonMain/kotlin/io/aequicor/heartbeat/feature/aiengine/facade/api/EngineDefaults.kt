package io.aequicor.heartbeat.feature.aiengine.facade.api

/** Initial engine choice supplied by the application; saved or explicitly selected routes take precedence. */
public interface EngineDefaults {
    /**
     * Returns the unique registration with [EngineDescriptor.isDefault] that supports the current platform,
     * only while both [AiEngines] and that descriptor's toggle are on; otherwise `null`.
     * Never starts a runtime, constructs an adapter factory or chooses credentials.
     */
    public suspend fun preferred(): EngineId?
}
