package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason

/** Evaluate reported facts against the independent policy of the registered runtime factory. */
internal fun registeredCompatibility(
    registry: EngineRegistry,
    engine: EngineId,
    installation: Installation,
    report: EngineCompatibilityObservation?,
): EngineCompatibilityObservation? {
    if (report == null) return null
    val observed = report.evidence.identity
    val isWrongEngine = observed.engine != null && observed.engine != engine
    val isWrongVersion = installation.version != null && observed.runtime != null &&
        installation.version != observed.runtime
    if (report.requirements.engine != engine || isWrongEngine || isWrongVersion) {
        throw EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    }
    val policy = registry.require(engine).factory.value.runtimeRequirements ?: return null
    return if (policy.engine == engine) EngineCompatibilityObservation(policy, report.evidence) else null
}

/** Refuses evidence for a different installation or engine; missing facts remain unknown. */
internal fun validateCandidate(
    engine: EngineId,
    settings: LaunchSettings,
    managed: ManagedInstall?,
    result: EngineCandidateInspection,
) {
    if (result.engine != engine || !result.matchesSelection(settings, managed) || !result.matchesEvidence(engine)) {
        throw EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    }
}

private fun EngineCandidateInspection.matchesSelection(settings: LaunchSettings, managed: ManagedInstall?): Boolean {
    val expectedSource = when {
        !settings.executable.isNullOrBlank() -> InstallSource.Custom
        settings.runtime == RuntimePreference.System -> InstallSource.System
        settings.runtime == RuntimePreference.Managed -> InstallSource.Managed
        settings.runtime == RuntimePreference.Bundled -> InstallSource.Bundled
        settings.runtime == RuntimePreference.HostApp -> InstallSource.HostApp
        else -> null
    }
    val isWrongSource = installation.source != InstallSource.Missing && installation.source != expectedSource
    val isWrongPath = installation.source == InstallSource.Custom && installation.path != settings.executable
    val isWrongManaged = installation.source == InstallSource.Managed &&
        (managed == null || installation.path != managed.executable || installation.version != managed.version)
    val isWrongHostVersion = installation.source == InstallSource.HostApp && settings.hostRuntimeVersion != null &&
        installation.version != settings.hostRuntimeVersion
    return listOf(isWrongSource, isWrongPath, isWrongManaged, isWrongHostVersion).none { it }
}

private fun EngineCandidateInspection.matchesEvidence(engine: EngineId): Boolean {
    val report = compatibility
    val observedEngine = report?.evidence?.identity?.engine
    val isWrongIdentity = report != null &&
        (report.requirements.engine != engine || (observedEngine != null && observedEngine != engine))
    val reportedVersion = report?.evidence?.identity?.runtime
    val isWrongVersion =
        installation.version != null && reportedVersion != null && installation.version != reportedVersion
    return !isWrongIdentity && !isWrongVersion
}
