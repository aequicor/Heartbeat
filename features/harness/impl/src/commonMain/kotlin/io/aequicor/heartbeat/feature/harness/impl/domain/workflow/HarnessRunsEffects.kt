package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsEffect
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import kotlin.time.Clock

/** Profile machine projection; executing author code belongs to independently supervised driver jobs. */
internal typealias HarnessRunsMachine = Machine<HarnessRunsState, HarnessRunsIntent, HarnessRunsOutput>

/** Effects only restore or hand off ownership. Repeated requests coalesce in the profile coordinator. */
internal class HarnessRunsEffects(
    private val storage: HarnessRunStorage,
    private val drivers: ProfileWorkflowDrivers,
    private val execution: WorkflowRunExecution,
    private val clock: Clock,
) : EffectHandler<HarnessRunsEffect, HarnessRunsIntent> {
    override suspend fun handle(effect: HarnessRunsEffect, machine: EffectScope<HarnessRunsIntent>) {
        when (effect) {
            HarnessRunsEffect.Restore -> {
                val runs = storage.load()
                runs.forEach { execution.publish(it) }
                machine.send(HarnessRunsIntent.Internal.Restored(runs, clock.now()))
            }
            is HarnessRunsEffect.Drive -> effect.runs.forEach { drivers.drive(it) }
            is HarnessRunsEffect.StopRun -> drivers.drive(effect.run)
            is HarnessRunsEffect.Pause -> effect.runs.forEach { drivers.pause(it) }
        }
    }
}
