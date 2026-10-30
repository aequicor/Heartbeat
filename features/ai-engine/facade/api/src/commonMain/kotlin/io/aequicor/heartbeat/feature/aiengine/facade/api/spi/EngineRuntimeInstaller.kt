package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall

/**
 * Pinned native dependencies of an adapter, delivered independently of the installer. Uses the same verified,
 * staged installation store as engine management. Retained versions survive bundle updates and rollback until
 * explicit uninstall. Observation and [find] never download; [prepare] belongs to a cancellable user operation.
 * An adapter must validate provenance and contents in [EngineManager.inspect] before any candidate executes.
 */
public interface EngineRuntimeInstaller {
    /** A retained copy of exactly [version], without changing the active copy or consulting a release feed. */
    public suspend fun find(engine: EngineId, version: String, target: String? = null): ManagedInstall?

    /**
     * Reuses a validated retained copy, or resolves exactly [version] through [manager], downloads, checks and
     * activates it. A failed or cancelled check discards the candidate and leaves existing copies intact.
     * The adapter reports [progress] through its existing first-use preparation flow.
     */
    public suspend fun prepare(
        engine: EngineId,
        version: String,
        manager: EngineManager,
        target: String? = null,
        progress: suspend (EnginePreparation) -> Unit,
    ): ManagedInstall
}
