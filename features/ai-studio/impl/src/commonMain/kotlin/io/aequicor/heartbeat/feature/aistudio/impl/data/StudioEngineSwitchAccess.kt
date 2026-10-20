package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchFailure
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlinx.coroutines.flow.first

/** Repository storage callbacks keep replacement orchestration outside the general studio runtime. */
internal class StudioEngineSwitchAccess(
    private val facade: EngineFacade,
    private val workspaces: LocalWorkspaces,
    private val harnesses: StudioHarnessAttach,
    private val mutations: StudioEngineSwitchMutations,
    private val storage: StudioEngineSwitchStorage,
) : StudioEngineSwitchHost {
    private val log = Log.tag("StudioEngineSwitchAccess")

    override suspend fun <T> withIdleSource(id: String, body: suspend () -> T): T {
        log.v { "Serialize replacement with helper preparation" }
        return mutations.helpers.withIdleSource(storage.record(id), body)
    }

    override suspend fun switchRecord(id: String): StudioChatRecord {
        log.v { "Read replacement source" }
        return storage.record(id)
    }

    override suspend fun recoverSwitch(id: String) {
        if (storage.record(id).pendingEngineSwitch == null) return
        storage.handle(id)?.let { mutations.configurations.retire(id, it) }
        val recovered = mutations.carry.recoverEngineSwitch(id) ?: return
        storage.configurationState(id, StudioSessionConfiguration(checkNotNull(recovered.configuration)))
        log.i { "Recovered journaled chat engine replacement" }
    }

    override suspend fun validateSwitch(record: StudioChatRecord, target: EngineTarget): StudioModel {
        log.v { "Validate replacement route" }
        mutations.helpers.validate(record, target)
        storage.validate(target)
        val engine = facade.engines.state.value.firstOrNull { it.descriptor.id == target.engine }?.descriptor
        refuseUnless(engine != null, StudioEngineSwitchFailure.Unsupported)
        val descriptor = checkNotNull(engine)
        facade.engines.features(target.engine).requireFeature(CreatesSessions)
        record.validatedExecutionWorkspace(workspaces, descriptor.isLocalWorkspaceSupported)
        val catalog = facade.models.observe(target.engine, target.binding).first { it.isLoaded }
        return studioModel(
            target,
            catalog.models.firstOrNull { it.target == target },
            descriptor.title,
            "",
            descriptor.isLocalWorkspaceSupported,
        )
    }

    override suspend fun switchSource(id: String, target: EngineTarget): ActiveSession {
        log.v { "Resolve replacement source handle" }
        return storage.open(id, target)
    }

    override suspend fun refreshSwitchSource(id: String, active: ActiveSession) {
        log.v { "Refresh replacement source transcript" }
        storage.refresh(id, active)
    }

    override suspend fun commitSwitch(
        id: String,
        change: StudioEngineSwitch,
        active: ActiveSession?,
        settings: StudioSessionSettings,
    ) {
        log.i { "Persist replacement journal and route" }
        mutations.carry.switchEngine(id, active) { current ->
            refuseUnless(current.target == change.expected, StudioEngineSwitchFailure.Stale)
            val snapshot = current.harnessCarrySnapshot(harnesses)
            val selected = snapshot?.values?.flatten()?.toSet()?.plus(current.pendingHarnesses.orEmpty())
                ?: current.pendingHarnesses
            check(current.harnessCarrySnapshot(harnesses) == snapshot) { "Harness choices changed during replacement" }
            StudioEngineSwitchJournal(
                current.ref, change.expected, change.target, settings, current.contextSegment + 1,
                modelLabel(change.expected), modelLabel(change.target), selected,
                current.pendingHarnessSource ?: current.ref, mutations.helpers.identity(current, change.target),
            )
        }
        storage.configurationState(id, StudioSessionConfiguration(settings))
    }

    private fun modelLabel(target: EngineTarget): String = storage.models().firstOrNull {
        it.id == target.studioModelId()
    }?.name ?: "${target.engine.value} · ${target.binding.value} · ${target.model.value}"

    override fun switchState(id: String, state: StudioEngineSwitchState, onlyIfIdle: Boolean) {
        log.i { "Engine replacement state pending=${state.isPending} failure=${state.failure ?: "none"}" }
        storage.publish(id, state, onlyIfIdle)
    }
}

/** Storage and handle callbacks execute under the repository’s existing locks. */
internal data class StudioEngineSwitchStorage(
    val record: suspend (String) -> StudioChatRecord,
    val handle: suspend (String) -> ActiveSession?,
    val open: suspend (String, EngineTarget) -> ActiveSession,
    val validate: suspend (EngineTarget) -> Unit,
    val configurationState: suspend (String, StudioSessionConfiguration) -> Unit,
    val models: () -> List<StudioModel>,
    val publish: (String, StudioEngineSwitchState, Boolean) -> Unit,
    val refresh: suspend (String, ActiveSession) -> Unit,
)

/** The barriers and journals participating in one replacement. */
internal data class StudioEngineSwitchMutations(
    val configurations: StudioConfigurationController,
    val carry: StudioContextCarry,
    val helpers: StudioEngineSwitchHelpers,
)
