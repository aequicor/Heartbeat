package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.UiEditRejection
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.api.UiWrite
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** How a library write of a tool ended. */
internal sealed interface WriteOutcome {
    /** Saved; [surface] is the created or changed surface. */
    data class Applied(val surface: UiSurface?) : WriteOutcome

    /** Nothing changed, for [reason]. */
    data class Rejected(val reason: UiEditRejection) : WriteOutcome

    /** The machine did not take the write. */
    data object NotTaken : WriteOutcome

    /** The write was taken, but its outcome did not arrive in time. */
    data object Unconfirmed : WriteOutcome
}

private val log = Log.tag("ServiceUiTools")
private val OutcomeTimeout = 10.seconds

/**
 * Sends [write] to the machine and waits for its outcome. Outputs are hot without replay, so the outcome is observed
 * before the write is sent.
 */
internal suspend fun ServiceUiMachine.submit(write: UiWrite): WriteOutcome = coroutineScope {
    val outcome = async(start = CoroutineStart.UNDISPATCHED) {
        outputs.mapNotNull { it.outcomeOf(write.id) }.first()
    }
    try {
        log.d { "Submit interface library write ${write.id}" }
        if (send(ServiceUiIntent.Internal.Edit(write)) != SendResult.Accepted) {
            WriteOutcome.NotTaken
        } else {
            withTimeoutOrNull(OutcomeTimeout) { outcome.await() } ?: WriteOutcome.Unconfirmed
        }
    } finally {
        outcome.cancel()
    }
}

private fun ServiceUiOutput.outcomeOf(id: String): WriteOutcome? = when (this) {
    is ServiceUiOutput.EditApplied -> WriteOutcome.Applied(surface).takeIf { writeId == id }
    is ServiceUiOutput.EditRejected -> WriteOutcome.Rejected(reason).takeIf { writeId == id }
    is ServiceUiOutput.PromptRequested -> null
}

/** What the agent learns about a write that changed nothing. */
internal fun WriteOutcome.failureText(): String = when (this) {
    is WriteOutcome.Applied -> "saved"

    WriteOutcome.NotTaken -> "interfaces are not available now"

    WriteOutcome.Unconfirmed -> "not confirmed yet: the library is still saving; check with ui_get before retrying"

    is WriteOutcome.Rejected -> when (reason) {
        UiEditRejection.Conflict ->
            "the interface changed meanwhile or already exists; read its revision with ui_get and retry"

        UiEditRejection.Missing -> "the interface does not exist"

        UiEditRejection.Limit -> LIBRARY_FULL

        UiEditRejection.Busy -> "another change of the library is being saved; retry shortly"

        UiEditRejection.Storage -> "the library could not be saved; nothing changed"

        UiEditRejection.Unavailable -> "interfaces are turned off or not loaded yet"
    }
}

private const val BYTES_IN_KIB = 1024
private const val LIBRARY_FULL = "the library is full: at most ${ServiceUiLimits.SURFACES} interfaces, " +
    "${ServiceUiLimits.SURFACES_PER_OWNER} per owner and ${ServiceUiLimits.LIBRARY_BYTES / BYTES_IN_KIB} KiB"
