package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import kotlinx.coroutines.flow.StateFlow

/**
 * Heartbeat's own verified copies of engine executables, one active copy per engine, shared by every profile of
 * the device. Installing is two-phase: [stage] downloads, verifies and unpacks a candidate that nothing runs yet,
 * the caller checks it, then [activate] switches to it atomically or [discard] removes it. Failures are
 * [io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException]s; a failed step leaves the active copy
 * untouched and no partial files behind.
 */
interface ManagedInstallStore {
    /** Active copy per engine; empty until [refresh] read the disk. */
    val state: StateFlow<Map<EngineId, ManagedInstall>>

    /** Reads the active copies from disk and removes leftovers of interrupted operations. */
    suspend fun refresh()

    /** Downloads, verifies and unpacks [plan] for [engine] without activating it. */
    suspend fun stage(engine: EngineId, plan: InstallPlan, progress: suspend (InstallStep) -> Unit): StagedInstall

    /** Makes [staged] the active copy of its engine and removes the previous one. */
    suspend fun activate(staged: StagedInstall): ManagedInstall

    /** Removes [staged] without activating it. */
    suspend fun discard(staged: StagedInstall)

    /** Removes the active copy of [engine]; nothing happens when there is none. */
    suspend fun uninstall(engine: EngineId)
}

/** A verified, unpacked candidate; [candidate] points at its executable for a check run before activation. */
data class StagedInstall(
    val engine: EngineId,
    val candidate: ManagedInstall,
    internal val token: String,
    internal val sha256: String,
) {
    override fun toString(): String = "StagedInstall(engine=${engine.value}, version=${candidate.version})"
}

/** Progress of [ManagedInstallStore.stage]. */
sealed interface InstallStep {
    /** [bytes] of [total] downloaded; [total] is null when unknown. */
    data class Downloading(val bytes: Long, val total: Long?) : InstallStep

    /** The download matched the publisher's checksum. */
    data object Verified : InstallStep

    /** The release is being unpacked. */
    data object Unpacking : InstallStep
}
