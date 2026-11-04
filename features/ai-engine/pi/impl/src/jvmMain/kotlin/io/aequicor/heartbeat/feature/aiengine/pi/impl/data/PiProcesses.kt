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

    /** Read-only: true only when no stored identity still names a live process; never signals. */
    suspend fun hasExited(owner: PiExecutionOwner): Boolean = false

    /**
     * Whether the exact bytes of [admission] are still the artifact an admission verified: the managed copy's
     * recorded executable digest or the pinned bundled inventory. Never resolves a launch or repairs anything.
     */
    suspend fun verifyAdmission(admission: PiRuntimeAdmission): Boolean = false

    /**
     * Starts Pi from [admission] when present, instead of re-resolving the profile's current launch at this moment.
     * The caller re-verifies [admission] before and after the start and refuses the runtime on a mismatch.
     */
    suspend fun start(
        source: AuthSource.ManagedKey,
        workspace: String?,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
        plan: PiLaunchPlan? = null,
        admission: PiRuntimeAdmission? = null,
    ): PiConnection
}
