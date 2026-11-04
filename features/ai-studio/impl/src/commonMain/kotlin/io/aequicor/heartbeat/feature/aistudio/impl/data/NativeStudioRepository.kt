package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioLastStartedRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPreferences
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/** Native engines own their transcript and branch mutations; scripted repositories supply local writers. */
internal abstract class NativeStudioRepository(
    private val preferences: StudioPreferences,
    private val selections: ModelSelections,
    private val efforts: EffortChoicesView,
    private val lastStarted: StudioLastStartedRun,
) : StudioRepository,
    StudioRuntime {
    private val log = Log.tag("NativeStudioRepository")
    override suspend fun defaults(): RunSettings {
        log.v { "Read start-page defaults" }
        return observeDefaults().first()
    }

    override fun snapshotEfforts(modelIds: List<String>): Map<String, String?> {
        log.v { "Snapshot start-page effort choices" }
        val state = efforts.state.value as? EffortConfigurationState.Ready
        return modelIds.mapNotNull { id ->
            studioModelTarget(id)?.let { id to state?.effortFor(it) }
        }.toMap()
    }

    override fun latestAccepted(): StudioSessionSettings? {
        log.v { "Read latest accepted start-page receipt" }
        return lastStarted.latest()
    }

    override fun observeDefaults(): Flow<RunSettings> {
        log.v { "Observe start-page defaults" }
        return observeStudioDefaults(preferences, selections, efforts, lastStarted)
    }

    override suspend fun saveDefaults(settings: RunSettings, version: StudioSettingsVersion) {
        log.d { "Save start-page preferences revision=${version.revision}" }
        preferences.save(settings, version)
    }

    override suspend fun defaultProjectId(): String? {
        log.d { "Read default workspace" }
        return null
    }

    override fun newMessageId(): String = Uuid.random().toString().also { log.d { "Allocate message identity" } }
    override suspend fun append(sessionId: String, message: StudioMessage) {
        log.w { "Rejected local transcript append" }
        error("Native history owns transcript writes")
    }

    override suspend fun replace(sessionId: String, message: StudioMessage) {
        log.w { "Rejected local transcript replacement" }
        error("Native history owns transcript writes")
    }

    override suspend fun setBranch(sessionId: String, branch: String) {
        log.w { "Rejected local branch mutation" }
        error("Native tools own branch metadata")
    }
}
