package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolChoices

/** Opens only the current route under the repository's conversation lock, before any prompt is submitted. */
internal class StudioSessionOpening(
    private val facade: EngineFacade,
    private val workspaces: LocalWorkspaces,
    private val harnesses: StudioHarnessAttach,
    private val carry: StudioContextCarry,
    private val usage: EngineStudioUsage,
    private val storage: StudioSessionStorage,
) {
    /** The caller holds the conversation lock; this path never updates the saved model or carries declarations. */
    suspend fun openRecovery(record: StudioChatRecord, recovery: StudioRecoveryOpening): ActiveSession {
        check(record.hasSameRecoveryRoute(storage.readRecord(record.id))) { "Recovery route changed before opening" }
        val current = storage.readHandle(record.id)
        val active = recovery.open(record, current)
        if (active !== current) {
            storage.rememberRecovery(record, active)
            usage.attach(record.id, active)
        }
        return active
    }

    suspend fun open(id: String, target: EngineTarget, chosen: ToolChoices): ActiveSession {
        val record = carry.prepareHarnesses(id, harnesses)
        val workspace = record.validatedExecutionWorkspace(
            workspaces,
            isLocalWorkspaceSupported = facade.engines.state.value.any {
                it.descriptor.id == target.engine && it.descriptor.isLocalWorkspaceSupported
            },
        )
        val current = storage.readHandle(id)
        if (current != null) {
            check(
                current.route.engine == target.engine && current.route.binding == target.binding,
            ) { "Start a new conversation to change engine or connection" }
            check(current.route.workspace == workspace) { "The conversation workspace cannot change" }
            if (current.configurationModel(record.target?.model) != target.model) {
                current.features.requireFeature(SwitchesModels).switchTo(target.model)
            }
            storage.update(id) { copy(target = target) }
            return current
        }
        val hasCarriedHarnesses = record.ref == null && record.isHarnessCarryEnabled(harnesses)
        val active = if (record.ref == null) {
            facade.engines.features(target.engine).requireFeature(CreatesSessions)
                .create(
                    studioCreateRequest(target, workspace, record.withCarriedHarnesses(chosen), record.logicalSession),
                )
        } else {
            check(
                record.target?.engine == target.engine && record.target.binding == target.binding,
            ) { "The stored session uses another connection" }
            facade.sessions.get(record.ref).features.requireFeature(ResumesSessions)
                .resume(studioResumeRequest(target, workspace, record.logicalSession))
        }
        // Register before persisting: the stored ref recomputes continuability, which must see the live handle.
        storage.registerHandle(id, active)
        storage.persistReference(
            active,
            { storage.update(id) { withNativeReference(target, active.ref, hasCarriedHarnesses) } },
            { storage.forgetHandle(id, active) },
        )
        usage.attach(id, active)
        return active
    }
}

/** Native handle writes and record persistence are owned by the repository. */
internal data class StudioSessionStorage(
    val readHandle: suspend (String) -> ActiveSession?,
    val registerHandle: suspend (String, ActiveSession) -> Unit,
    val forgetHandle: suspend (String, ActiveSession) -> Unit,
    val update: suspend (String, StudioChatRecord.() -> StudioChatRecord) -> Unit,
    val persistReference: suspend (ActiveSession, suspend () -> Unit, suspend () -> Unit) -> Unit,
    val readRecord: suspend (String) -> StudioChatRecord,
) {
    private val log = Log.tag("StudioSessionStorage")

    /** The cleanup boundary owns the resumed handle before registration can suspend or fail. */
    suspend fun rememberRecovery(record: StudioChatRecord, active: ActiveSession) {
        log.v { "Register the recovered handle inside its cleanup boundary" }
        persistReference(
            active,
            {
                registerHandle(record.id, active)
                val current = readRecord(record.id)
                check(current.hasSameRecoveryRoute(record)) { "Recovery route changed while opening" }
            },
            { forgetHandle(record.id, active) },
        )
    }
}
