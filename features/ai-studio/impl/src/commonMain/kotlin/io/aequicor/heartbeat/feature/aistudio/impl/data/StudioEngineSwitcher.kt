package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchFailure
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchState
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitching
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** IO owned by the studio repository, including its conversation lock and durable journal. */
internal interface StudioEngineSwitchHost {
    suspend fun <T> withIdleSource(id: String, body: suspend () -> T): T
    suspend fun switchRecord(id: String): StudioChatRecord
    suspend fun recoverSwitch(id: String)
    suspend fun validateSwitch(record: StudioChatRecord, target: EngineTarget): StudioModel
    suspend fun switchSource(id: String, target: EngineTarget): ActiveSession
    suspend fun refreshSwitchSource(id: String, active: ActiveSession)
    suspend fun commitSwitch(
        id: String,
        change: StudioEngineSwitch,
        active: ActiveSession?,
        settings: StudioSessionSettings,
    )
    fun switchState(id: String, state: StudioEngineSwitchState, onlyIfIdle: Boolean = false)
}

/** A profile operation reserves the same chat as user and background turns; it never submits a native request. */
internal class StudioEngineSwitcher(
    private val profile: ScopeHandle,
    private val toggles: FeatureToggles,
    private val runs: StudioRunCoordinator,
    private val configurations: StudioConfigurationController,
    private val host: StudioEngineSwitchHost,
) {
    private val log = Log.tag("StudioEngineSwitcher")

    suspend fun switch(id: String, change: StudioEngineSwitch) =
        profile.coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
            if (!isEnabled(id)) {
                return@async
            }
            try {
                configurations.exclusive(id) { runs.mutate(id) { perform(id, change) } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Chat engine replacement reservation refused" }
                host.switchState(
                    id,
                    StudioEngineSwitchState(failure = StudioEngineSwitchFailure.Busy),
                    onlyIfIdle = true,
                )
            }
        }.await()

    private suspend fun perform(id: String, change: StudioEngineSwitch) {
        var failure: StudioEngineSwitchFailure? = null
        host.switchState(id, StudioEngineSwitchState(isPending = true))
        try {
            log.i { "Prepare chat engine replacement" }
            host.withIdleSource(id) { apply(id, change) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: StudioEngineSwitchRefused) {
            log.w(error) { "Chat engine replacement refused" }
            failure = error.reason
        } catch (error: Exception) {
            log.e(error) { "Chat engine replacement failed; retain its journal for recovery" }
            failure = StudioEngineSwitchFailure.Unavailable
        } finally {
            // Publish before releasing either barrier: an older completion cannot hide the next operation.
            withContext(NonCancellable) { host.switchState(id, StudioEngineSwitchState(failure = failure)) }
        }
        log.i { "Chat engine replacement finished success=${failure == null}" }
    }

    private suspend fun isEnabled(id: String): Boolean {
        if (toggles.get(StudioEngineSwitching)) return true
        host.switchState(id, StudioEngineSwitchState(failure = StudioEngineSwitchFailure.Disabled), onlyIfIdle = true)
        return false
    }

    private suspend fun apply(id: String, change: StudioEngineSwitch) {
        host.recoverSwitch(id)
        val record = host.switchRecord(id)
        refuseUnless(record.organismId == null, StudioEngineSwitchFailure.Unsupported)
        refuseUnless(record.target == change.expected, StudioEngineSwitchFailure.Stale)
        if (change.target == change.expected) return
        refuseUnless(
            change.target.engine != change.expected.engine ||
                change.target.binding != change.expected.binding,
            StudioEngineSwitchFailure.Unsupported,
        )
        val model = host.validateSwitch(record, change.target)
        val active = record.ref?.let { host.switchSource(id, change.expected) }
        if (active != null) {
            refuseUnless(active.canCarryEnvironment(), StudioEngineSwitchFailure.Busy)
            refuseUnless(active.isEngineSwitchIdle(), StudioEngineSwitchFailure.Busy)
            host.refreshSwitchSource(id, active)
            refuseUnless(active.canCarryEnvironment(), StudioEngineSwitchFailure.Busy)
            configurations.retire(id, active)
        }
        val previous = record.configuration
        val settings = StudioSessionSettings(
            change.target.studioModelId(),
            previous?.reasoningEffort?.takeIf { it in model.reasoningEfforts }
                ?: model.defaultReasoningEffort,
            previous?.approval ?: io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode.Ask,
        )
        host.commitSwitch(id, change, active, settings)
    }
}

internal class StudioEngineSwitchRefused(val reason: StudioEngineSwitchFailure) : IllegalStateException(reason.name)

internal fun refuseUnless(condition: Boolean, reason: StudioEngineSwitchFailure) {
    if (!condition) throw StudioEngineSwitchRefused(reason)
}
