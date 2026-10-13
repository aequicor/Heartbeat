package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import io.aequicor.heartbeat.feature.serviceasui.api.UiEditRejection
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.WriteOutcome

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
