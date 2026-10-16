package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import kotlinx.collections.immutable.toImmutableSet

/** Imports of the engine's own configuration into the profile of the panel of [engineId]. */
internal class EngineImports(
    private val machine: Machine<EngineControlState, EngineControlIntent, EngineControlOutput>,
    private val engineId: EngineId,
) {
    private val log = Log.tag("EngineImports")

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    suspend fun PanelContext.handle(intent: ImportIntent) {
        when (intent) {
            ImportIntent.Preview -> apply(ControlOperation.PreviewImport(engineId))

            is ImportIntent.Toggle -> updateState {
                copy(
                    importing = importing?.let { current ->
                        val selected = if (intent.item in current.selected) {
                            current.selected - intent.item
                        } else {
                            current.selected + intent.item
                        }
                        current.copy(selected = selected.toImmutableSet())
                    },
                )
            }

            is ImportIntent.SetReplacing -> updateState {
                copy(importing = importing?.copy(isReplacing = intent.isReplacing))
            }

            ImportIntent.Import -> import()

            ImportIntent.Dismiss -> {
                sendTo(machine, EngineControlIntent.Public.DismissImport)
                updateState { copy(importing = null, importReport = null) }
            }
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.import() {
        var screen = EngineControlPanelState(engineId.value)
        withState { screen = this }
        val importing = screen.importing
        val config = machine.state.value.configOf(engineId)
        // An import saves the profile: unsaved changes would be in conflict with it.
        val isBlocked = screen.config?.isDirty == true || importing?.selected.isNullOrEmpty()
        if (importing == null || config == null || isBlocked) {
            log.w { "ignore an import without parts or over unsaved changes" }
            return
        }
        val mode = if (importing.isReplacing) ImportMode.Replace else ImportMode.Merge
        apply(ControlOperation.Import(engineId, ImportSelection(importing.selected, mode), config.revision))
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelContext.apply(operation: ControlOperation) {
        val result = sendTo(machine, EngineControlIntent.Public.Apply(operation))
        if (result != SendResult.Accepted) log.w { "${operation::class.simpleName.orEmpty()} not accepted: $result" }
    }
}
