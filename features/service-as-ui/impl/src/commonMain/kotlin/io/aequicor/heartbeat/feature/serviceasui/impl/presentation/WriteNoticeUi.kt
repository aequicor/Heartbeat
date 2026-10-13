package io.aequicor.heartbeat.feature.serviceasui.impl.presentation

import io.aequicor.heartbeat.feature.serviceasui.api.UiEditRejection
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.WriteOutcome

/** Why a change the user made in the library screens was not saved. */
internal enum class WriteNoticeUi {
    /** The interface changed meanwhile, for example by the agent. */
    Changed,

    /** The interface no longer exists. */
    Missing,

    /** The library limits would be exceeded. */
    Full,

    /** Another change is being saved; try again. */
    Busy,

    /** Storage failed; nothing changed. */
    Failed,

    /** The feature is off or its library is not loaded. */
    Unavailable,

    /** The change was taken, but its outcome did not arrive in time. */
    Unconfirmed,
}

/** What the user is told about this outcome; null when the change was saved. */
internal fun WriteOutcome.notice(): WriteNoticeUi? = when (this) {
    is WriteOutcome.Applied -> null

    WriteOutcome.NotTaken -> WriteNoticeUi.Unavailable

    WriteOutcome.Unconfirmed -> WriteNoticeUi.Unconfirmed

    is WriteOutcome.Rejected -> when (reason) {
        UiEditRejection.Conflict -> WriteNoticeUi.Changed
        UiEditRejection.Missing -> WriteNoticeUi.Missing
        UiEditRejection.Limit -> WriteNoticeUi.Full
        UiEditRejection.Busy -> WriteNoticeUi.Busy
        UiEditRejection.Storage -> WriteNoticeUi.Failed
        UiEditRejection.Unavailable -> WriteNoticeUi.Unavailable
    }
}
