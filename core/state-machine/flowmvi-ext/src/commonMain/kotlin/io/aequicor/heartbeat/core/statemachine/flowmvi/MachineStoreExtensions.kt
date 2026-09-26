package io.aequicor.heartbeat.core.statemachine.flowmvi

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.dsl.StoreBuilder
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Makes the store reflect [machine] while the screen is subscribed: every machine state is mapped into the
 * store state by [reduce], every machine output is passed to [onOutput] (usually `action(...)`).
 * The store keeps no copy of the machine state — only what [reduce] derives from it plus local UI input.
 *
 * Write [reduce] as a `when` over the machine state **without `else`**: the compiler then forces the screen to
 * handle every state the machine declares.
 *
 * ```
 * reflect(chat, onOutput = { output ->
 *     when (output) {
 *         ChatOutput.Generated -> action(ChatScreenAction.ScrollToBottom)
 *     }
 * }) { machineState ->
 *     when (machineState) {
 *         ChatState.Idle, is ChatState.Loading -> copy(phase = Phase.Loading)
 *         is ChatState.Ready -> copy(phase = Phase.Content(isGenerating = false))
 *         is ChatState.Generating -> copy(phase = Phase.Content(isGenerating = true))
 *         is ChatState.Error -> copy(phase = Phase.Error)
 *     }
 * }
 * ```
 *
 * Derive the initial store state the same way: `initial = ChatScreenState().reduceChat(chat.state.value)`.
 * Mirroring survives the last unsubscription for `stopDelay` (configuration changes).
 * Every mirrored update and output is logged under `MVI/<store name>`.
 */
public fun <S, I, A, MS, MO> StoreBuilder<S, I, A>.reflect(
    machine: MachineRef<MS, *, MO>,
    stopDelay: Duration = 1.seconds,
    onOutput: suspend PipelineContext<S, I, A>.(output: MO) -> Unit = {},
    reduce: S.(machineState: MS) -> S,
): Unit where S : MVIState, I : MVIIntent, A : MVIAction, MS : MachineState, MO : MachineOutput {
    whileSubscribed(name = "reflect:${machine.name}", stopDelay = stopDelay) {
        val log = storeLog()
        log.d { "reflecting machine ${machine.name}" }
        coroutineScope {
            launch {
                machine.outputs.collect { output ->
                    log.d { "machine ${machine.name}: output ${output.label()}" }
                    onOutput(output)
                }
            }
            machine.state.collect { machineState ->
                updateState {
                    val next = reduce(machineState)
                    log.d { "machine ${machine.name}: ${machineState.label()} → store state ${next.label()}" }
                    next
                }
            }
        }
    }
}

/**
 * Sends [intent] to [machine] from a store (usually from `reduce`). The machine decides; the store only reacts to
 * a rejection through [onRejected] (e.g. shows a snackbar) — never re-check the machine's guards in the store.
 * Rejections are logged as WARN under `MVI/<store name>`.
 */
// PipelineContext is FlowMVI's pipeline receiver (itself a CoroutineScope); store DSL functions extend it the same way.
@Suppress("SuspendFunWithCoroutineScopeReceiver")
public suspend fun <P : MachineIntent> PipelineContext<*, *, *>.sendTo(
    machine: MachineRef<*, P, *>,
    intent: P,
    onRejected: suspend (SendResult) -> Unit = {},
): SendResult {
    val log = storeLog()
    log.d { "→ ${machine.name}: ${intent.label()}" }
    val result = machine.send(intent)
    if (result != SendResult.Accepted) {
        log.w { "→ ${machine.name}: ${intent.label()} rejected ($result)" }
        onRejected(result)
    }
    return result
}

private fun PipelineContext<*, *, *>.storeLog(): Log = Log.tag("MVI/${config.name ?: "store"}")

/** Class name only: states and intents may hold user content or secrets. */
private fun Any.label(): String = this::class.simpleName ?: "?"
