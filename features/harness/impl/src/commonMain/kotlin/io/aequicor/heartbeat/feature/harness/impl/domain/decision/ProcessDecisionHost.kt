package io.aequicor.heartbeat.feature.harness.impl.domain.decision

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionLedger
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsEffect
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsIntent
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsOutput
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal typealias ProcessDecisionsMachine = Machine<
    ProcessDecisionsState,
    ProcessDecisionsIntent,
    ProcessDecisionsOutput,
>

/** Machine effects propagate failures to the standard fail-closed transition; no payload reaches a log. */
internal class ProcessDecisionEffects(private val storage: ProcessDecisionStorage) :
    EffectHandler<ProcessDecisionsEffect, ProcessDecisionsIntent> {
    override suspend fun handle(effect: ProcessDecisionsEffect, machine: EffectScope<ProcessDecisionsIntent>) {
        when (effect) {
            ProcessDecisionsEffect.Load -> machine.send(ProcessDecisionsIntent.Internal.Loaded(storage.load()))

            is ProcessDecisionsEffect.Save -> {
                storage.save(effect.write.ledger, effect.expectedRevision)
                machine.send(ProcessDecisionsIntent.Internal.Saved(effect.write))
            }
        }
    }
}

/** Owner-local transaction client. Script surfaces never receive this object or its write-capable machine. */
internal class ProcessDecisionHost(val machine: ProcessDecisionsMachine, private val clock: Clock) {
    suspend fun ready(): ProcessDecisionLedger? = withTimeoutOrNull(10.seconds) {
        if (machine.state.value == ProcessDecisionsState.Idle || machine.state.value == ProcessDecisionsState.Failed) {
            machine.send(ProcessDecisionsIntent.Public.Load)
        }
        val current = machine.state.first { it is ProcessDecisionsState.Ready || it == ProcessDecisionsState.Failed }
        (current as? ProcessDecisionsState.Ready)?.ledger
    }

    /** Await exactly our receipt. A timeout is not a grant, even if the write may have committed. */
    suspend fun edit(edit: ProcessDecisionEdit): Boolean = withTimeoutOrNull(10.seconds) {
        if (ready() == null) return@withTimeoutOrNull false
        coroutineScope {
            val request = Uuid.random().toHexString()
            val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                machine.outputs.first { it.request == request }
            }
            if (machine.send(ProcessDecisionsIntent.Internal.Edit(request, edit, clock.now())) != SendResult.Accepted) {
                receipt.cancel()
                false
            } else {
                receipt.await().isAccepted
            }
        }
    } ?: false
}
