package io.aequicor.heartbeat.feature.harness.api.decision

import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandRefusal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Durable native outcome of one guarded command. It contains no output, arguments or external-system authority.
 * Even [Exited] with code zero requires separate evidence of the business result. Timeouts and interrupted
 * execution have no receipt: their consumed decision remains Executing until an authoritative reconciliation.
 */
@Serializable
public sealed interface ProcessCommandReceipt {
    /** The native command returned this exit code; stdout and stderr are deliberately not retained. */
    @Serializable
    @SerialName("exited")
    public data class Exited(val exitCode: Int) : ProcessCommandReceipt

    /** The command runner refused before starting a process. The original decision is still consumed. */
    @Serializable
    @SerialName("refused")
    public data class Refused(val reason: ScriptCommandRefusal) : ProcessCommandReceipt
}
