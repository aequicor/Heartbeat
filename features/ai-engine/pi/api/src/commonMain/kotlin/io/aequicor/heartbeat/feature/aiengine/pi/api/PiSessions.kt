package io.aequicor.heartbeat.feature.aiengine.pi.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions

/** Pi sessions must expose context telemetry; only an unknown measurement may be null. */
public interface PiActiveSession : ActiveSession {
    /** Required telemetry, also published under the [SessionContextUsage] feature key. */
    public val contextUsage: SessionContextUsage
}

/** Compile-time requirement for both new and resumed Pi sessions to provide context telemetry. */
public interface PiSessions :
    CreatesSessions,
    AttachesSessions {
    /** Creates a session with mandatory context telemetry. */
    override suspend fun create(request: CreateSessionRequest): PiActiveSession

    /** Resumes a session with mandatory context telemetry. */
    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): PiActiveSession
}
