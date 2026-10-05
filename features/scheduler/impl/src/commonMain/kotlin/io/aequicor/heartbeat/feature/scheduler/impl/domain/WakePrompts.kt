package io.aequicor.heartbeat.feature.scheduler.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt

/**
 * The prompt that resumes a sleeping session: a short visible line for the transcript and a host directive with the
 * reason, the event payload and the sleeper's own note. The request id is stable per wake, so a repeated delivery
 * after a restart is recognisable.
 */
internal fun wakePrompt(delivery: WakeDelivery): WakePrompt {
    val wake = delivery.wake
    val reason = delivery.reason
    val visible = when (reason) {
        is WakeReason.Event -> "⏰ Пробуждение: событие ${reason.event.key}"
        is WakeReason.Deadline -> "⏰ Пробуждение: наступило время ${reason.at}"
    }
    val directive = buildString {
        append("Scheduler wake ${wake.id}: you put this session to sleep with ${SchedulerTools.SLEEP}")
        appendLine(" at ${wake.createdAt}.")
        when (reason) {
            is WakeReason.Event -> {
                val event = reason.event
                appendLine("Woken by event ${event.key} from ${event.origin.describe()} at ${event.at}.")
                reason.event.payload?.let { appendLine("Event payload:").appendLine(it) }
            }

            is WakeReason.Deadline -> appendLine("Woken because the deadline ${reason.at} passed.")
        }
        if (wake.request.note.isNotBlank()) appendLine("Your note for this moment:").appendLine(wake.request.note)
        append("Continue the task from here; sleep again with ${SchedulerTools.SLEEP} if you still need to wait.")
    }
    return WakePrompt(RequestId("wake_${wake.id.value}"), visible, directive)
}

private fun EventOrigin.describe(): String = when (this) {
    EventOrigin.Host -> "the host"
    EventOrigin.System -> "the system"
    is EventOrigin.Session -> "another session"
    is EventOrigin.Action -> "background action ${action.value}"
}
