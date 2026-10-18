package io.aequicor.heartbeat.feature.harness.api.decision

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlin.time.Instant

/** A host decision transaction is visible only after its durable receipt. Unknown writes force a fresh load. */
public sealed interface ProcessDecisionsState : MachineState {
    /** No requests or approvals can execute before loading the protected journal. */
    public data object Idle : ProcessDecisionsState

    /** Restoring durable decisions; script-owned data is never consulted as approval. */
    public data object Loading : ProcessDecisionsState

    /** Last committed book and at most one proposed durable write. */
    public data class Ready(val ledger: ProcessDecisionLedger, val pending: ProcessDecisionWrite? = null) :
        ProcessDecisionsState

    /** Fail closed: retry loading before answering or executing any request. */
    public data object Failed : ProcessDecisionsState
}

/** Exact proposal receipt, echoed by storage. Payloads never enter diagnostics. */
public data class ProcessDecisionWrite(val request: String, val ledger: ProcessDecisionLedger) {
    override fun toString(): String = "ProcessDecisionWrite(***)"
}

/** Only lifecycle observation is addressable outside harness; edits stay within the trusted owner. */
public sealed interface ProcessDecisionsIntent : MachineIntent {
    /** Addressable read-only operations. */
    public sealed interface Public : ProcessDecisionsIntent {
        /** Starts or retries restoration. */
        public data object Load : Public
    }

    /** Host-only inputs; no agent tool or script method dispatches Answer or Revoke. */
    public sealed interface Internal : ProcessDecisionsIntent {
        /** Storage's committed snapshot. */
        public data class Loaded(val ledger: ProcessDecisionLedger) : Internal

        /** Requested pure edit at host time. */
        public data class Edit(val request: String, val edit: ProcessDecisionEdit, val at: Instant) : Internal

        /** Storage committed this exact proposal. */
        public data class Saved(val write: ProcessDecisionWrite) : Internal

        /** Restoration failed or a write's outcome is uncertain. */
        public data object Failed : Internal
    }
}

/** IO commands; the machine does not persist an in-flight proposal as a committed approval. */
public sealed interface ProcessDecisionsEffect : MachineEffect {
    /** Read the private fail-closed book. */
    public data object Load : ProcessDecisionsEffect

    /** Compare and save the next ledger revision. */
    public data class Save(val write: ProcessDecisionWrite, val expectedRevision: Long) : ProcessDecisionsEffect
}

/** Correlated receipt; accepted is true only for an unchanged exact request or a committed edit. */
public data class ProcessDecisionsOutput(val request: String, val isAccepted: Boolean) : MachineOutput

/** Profile host journal; external features have no write-capable address. */
public object ProcessDecisionsMachineKey : MachineKey<
    ProcessDecisionsState,
    ProcessDecisionsIntent,
    ProcessDecisionsIntent.Public,
    ProcessDecisionsEffect,
    ProcessDecisionsOutput,
> {
    override val name: String = "process-decisions"
}

/**
 * Host-owned durable decisions, serialized through one pending transaction.
 *
 * | From | Intent / guard | To | Effect or output |
 * |---|---|---|---|
 * | Idle, Failed | Load | Loading | Load |
 * | Loading | Loaded | Ready | none |
 * | Loading, Ready | Failed | Failed | pending edit refused |
 * | Ready | Edit while pending or reducer refuses | stay | refused receipt |
 * | Ready | Edit exact request replay | stay | accepted receipt |
 * | Ready | Edit changes book | stay with pending | Save |
 * | Ready | Saved matching pending | stay with committed book | accepted receipt |
 * | any other | stale receipt or unsupported input | ignored | none |
 *
 * A failed save is never retried from memory: reload first to discover whether the durable claim committed. Thus
 * a restart between claim and external execution loses liveness rather than executing the command twice.
 */
public val ProcessDecisionsMachineSpec: MachineSpec<
    ProcessDecisionsState,
    ProcessDecisionsIntent,
    ProcessDecisionsEffect,
    ProcessDecisionsOutput,
> = machineSpec(ProcessDecisionsMachineKey, ProcessDecisionsState.Idle) {
    state<ProcessDecisionsState.Idle> {
        on<ProcessDecisionsIntent.Public.Load> {
            goto<ProcessDecisionsState.Loading> { ProcessDecisionsState.Loading }
            effect { ProcessDecisionsEffect.Load }
        }
    }
    state<ProcessDecisionsState.Failed> {
        on<ProcessDecisionsIntent.Public.Load> {
            goto<ProcessDecisionsState.Loading> { ProcessDecisionsState.Loading }
            effect { ProcessDecisionsEffect.Load }
        }
    }
    state<ProcessDecisionsState.Loading> {
        on<ProcessDecisionsIntent.Internal.Loaded> {
            goto<ProcessDecisionsState.Ready> { ProcessDecisionsState.Ready(intent.ledger) }
        }
        on<ProcessDecisionsIntent.Internal.Failed> {
            goto<ProcessDecisionsState.Failed> { ProcessDecisionsState.Failed }
        }
    }
    state<ProcessDecisionsState.Ready> {
        on<ProcessDecisionsIntent.Internal.Edit> {
            stay {
                val next = if (state.pending == null) state.ledger.reduce(intent.edit, intent.at) else null
                if (next == null || next == state.ledger) {
                    state
                } else {
                    state.copy(pending = ProcessDecisionWrite(intent.request, next))
                }
            }
            effect {
                val next = if (state.pending == null) state.ledger.reduce(intent.edit, intent.at) else null
                next?.takeUnless { it == state.ledger }?.let {
                    ProcessDecisionsEffect.Save(ProcessDecisionWrite(intent.request, it), state.ledger.revision)
                }
            }
            output {
                val next = if (state.pending == null) state.ledger.reduce(intent.edit, intent.at) else null
                when (next) {
                    null -> ProcessDecisionsOutput(intent.request, false)
                    state.ledger -> ProcessDecisionsOutput(intent.request, true)
                    else -> null
                }
            }
        }
        on<ProcessDecisionsIntent.Internal.Saved>(guard = { state.pending == intent.write }) {
            stay { ProcessDecisionsState.Ready(intent.write.ledger) }
            output { ProcessDecisionsOutput(intent.write.request, true) }
        }
        on<ProcessDecisionsIntent.Internal.Failed> {
            output { state.pending?.let { ProcessDecisionsOutput(it.request, false) } }
            goto<ProcessDecisionsState.Failed> { ProcessDecisionsState.Failed }
        }
    }
    onEffectFailure { _, _ -> ProcessDecisionsIntent.Internal.Failed }
}
