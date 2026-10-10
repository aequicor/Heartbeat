package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import kotlinx.serialization.json.JsonObject

/** Profile-native IO used by runtimes to validate credentials, locate transcripts and start a process. */
internal interface PiProcesses {
    suspend fun credentialFingerprint(source: AuthSource.ManagedKey): String
    suspend fun transcript(nativeId: String): String?

    /** Stops only persisted OS identities, recording observed descendants before any signal; never launches Pi. */
    suspend fun stop(
        owner: PiExecutionOwner,
        beginInspection: suspend () -> Boolean,
        record: suspend (PiExecutionOwner) -> PiExecutionOwner?,
    ): Boolean = false
    suspend fun start(
        source: AuthSource.ManagedKey,
        workspace: String?,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
        plan: PiLaunchPlan? = null,
    ): PiConnection
}
