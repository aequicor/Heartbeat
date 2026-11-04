package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Read-only file metadata for the exact explicit choice. The managed version is store metadata, not an observation
 * of the executable; no version, provenance, protocol, or baseline is established here. This result never admits
 * a process or licenses a write. A missing or non-executable choice is reported without trying another source.
 */
internal fun inspectPiCandidateMetadata(launch: LaunchContext, bundled: PiStartup? = null): EngineCandidateInspection? {
    val custom = launch.settings.executable?.takeIf { it.isNotBlank() }
    val managed = launch.managed
    val source = when {
        custom != null -> InstallSource.Custom
        launch.settings.runtime == RuntimePreference.Managed -> InstallSource.Managed
        launch.settings.runtime == RuntimePreference.Bundled -> InstallSource.Bundled
        else -> return null
    }
    val chosen = when {
        source == InstallSource.Custom -> custom
        source == InstallSource.Managed -> managed?.executable
        source == InstallSource.Bundled -> bundled?.takeIf { it.source == source }?.executable?.toString()
        else -> null
    }
    val path = try {
        chosen?.let(Path::of)?.takeIf { it.isAbsolute }
    } catch (error: InvalidPathException) {
        Log.tag("PiCandidateMetadata").w(error.withoutDetails()) { "Selected runtime path is invalid" }
        null
    }
    val version = candidateVersion(source, managed?.version, bundled?.runtimeVersion)
    val installation = candidateInstallation(path, source, version, chosen)
    return EngineCandidateInspection(PiEngineId, installation, compatibility = null)
}

private fun candidateVersion(source: InstallSource, managed: String?, bundled: String?): String? = when {
    source == InstallSource.Managed -> managed
    source == InstallSource.Bundled -> bundled
    else -> null
}

private fun candidateInstallation(path: Path?, source: InstallSource, version: String?, chosen: String?): Installation =
    if (path != null && Files.isRegularFile(path) && Files.isExecutable(path)) {
        Installation(source, version, chosen)
    } else {
        Installation(InstallSource.Missing, isRunnable = false)
    }
