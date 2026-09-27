package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSource

/** Lightweight access to one configured history store. Metadata access must not launch a process. */
public interface EngineSessionSource {
    /** Store identity; independent of the credentials that happened to create its sessions. */
    public val source: SessionSource

    /** Optional discovery API, looked up without IO. Null is reported as Unsupported coverage. */
    public val discovery: ListsSessions?

    /** Returns lazy history/resume capabilities; failures are domain exceptions, not empty history. */
    public suspend fun get(ref: SessionRef): EngineSession
}
