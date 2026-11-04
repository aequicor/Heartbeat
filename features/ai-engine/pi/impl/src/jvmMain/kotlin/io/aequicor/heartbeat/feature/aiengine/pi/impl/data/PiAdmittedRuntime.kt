package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import java.nio.file.Path

/**
 * One exact Pi artifact an admission verified: the executable the candidate checked, the launch snapshot it
 * describes and, for a managed copy, the store record whose `executableSha256` the bytes must keep matching.
 * A bundled artifact has no store record; its bytes are verified against the pinned runtime inventory instead.
 * The runtime starts this executable and never re-resolves the profile's current launch.
 */
internal data class PiRuntimeAdmission(val startup: PiStartup, val executable: Path, val managed: ManagedInstall?)

/** Whether [current] still selects exactly this artifact, executable and runtime version. */
internal fun PiRuntimeAdmission.matches(current: PiStartup): Boolean = current.source == startup.source &&
    current.executable == executable &&
    current.runtimeVersion == startup.runtimeVersion

/** A bundled admission has no store record: [PiRuntimeDistribution]'s pinned inventory verifies its bytes. */
internal val PiRuntimeAdmission.isBundled: Boolean get() = startup.source == InstallSource.Bundled

/** The runtime side of a Pi promotion: the exact executable and version it was built to start. */
internal interface PiAdmittedRuntime : EngineRuntime {
    /** Executable this runtime starts, or null for a runtime created outside admission. */
    val selectedExecutable: Path?

    /** Runtime version the admitted launch pinned, or null for a runtime created outside admission. */
    val admittedVersion: String?
}
