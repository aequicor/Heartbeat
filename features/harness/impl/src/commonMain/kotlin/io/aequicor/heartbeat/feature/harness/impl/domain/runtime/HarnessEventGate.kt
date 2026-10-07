package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Profile-local admission for enabled event sources; closing it precedes asynchronous machine suspension. */
internal class HarnessEventGate {
    private val state = MutableStateFlow(GateState())
    private val log = Log.tag("HarnessRuntime")
    val isEnabled: Boolean get() = state.value.isEnabled
    val currentEpoch: Long? get() = state.value.let { it.epoch.takeIf { _ -> it.isEnabled } }

    fun open() {
        log.v { "open harness event admission" }
        state.update { GateState(true, it.epoch + 1) }
    }

    fun close() {
        log.v { "close harness event admission" }
        state.update { it.copy(isEnabled = false) }
    }
}

private data class GateState(val isEnabled: Boolean = false, val epoch: Long = 0)
