package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Turns the user stopped from the host's session chrome; their later computer tool calls are refused in every
 * machine state until the turn ends. The machine keeps its own `stoppedOwners` only in Ready/Capturing, to fence
 * a capture call that passed this check before the stop; it forgets them when it leaves those states.
 */
@Inject
@SingleIn(ProfileScope::class)
internal class ComputerUseStoppedTurns {
    private val log = Log.tag("ComputerUseStoppedTurns")
    private val owners = MutableStateFlow<Set<CaptureOwner.Agent>>(emptySet())

    /** Does not suspend, so the machine effect records the stop before the next intent is handled. */
    fun stop(owner: CaptureOwner.Agent) {
        owners.update { it + owner }
        log.i { "agent turn stopped by the user; its computer tools are refused" }
    }

    fun isStopped(owner: CaptureOwner.Agent): Boolean = owner in owners.value

    /** A finished turn cannot call tools again, so its stop no longer needs to be remembered. */
    fun forget(owner: CaptureOwner.Agent) {
        if (owner !in owners.value) return
        owners.update { it - owner }
        log.d { "finished agent turn forgotten from stopped turns" }
    }
}
