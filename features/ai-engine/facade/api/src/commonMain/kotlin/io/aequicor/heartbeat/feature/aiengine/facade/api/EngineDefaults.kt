package io.aequicor.heartbeat.feature.aiengine.facade.api

/** Initial engine choice supplied by the application; saved or explicitly selected routes take precedence. */
public interface EngineDefaults {
    /** Returns the enabled default for this platform without starting a runtime or choosing credentials. */
    public suspend fun preferred(): EngineId?
}
