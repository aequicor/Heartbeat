package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioScheduledWaitTest {
    private val now = Instant.fromEpochSeconds(1_790_000_000)
    private val ref = SessionRef(EngineId("engine"), SessionSourceId("source"), "native")
    private val workspace = StudioWorkspace(
        emptyList(),
        listOf(
            StudioSession("chat", null, "Chat", now, nativeSession = ref),
            StudioSession("other", null, "Other", now, nativeSession = ref.copy(source = SessionSourceId("other"))),
            StudioSession("detached", null, "Detached", now),
        ),
    )

    @Test
    fun `a pending timer remains visible after the turn ends without marking the engine busy`() {
        val wake = wake("timer", WakeCondition(deadline = now))
        val working = AiStudioScreenState(running = persistentSetOf("chat"))
            .withWorkspace(workspace, SchedulerState.Ready(listOf(wake)))
        val sleeping = working.copy(running = persistentSetOf())
        assertEquals(SessionWaitUi.Sleeping, sleeping.session("chat")?.scheduledWait)
        assertFalse(sleeping.session("chat")!!.isRunning)
        assertEquals(SessionWaitUi.Sleeping, sidebarContent(sleeping).recent.first { it.id == "chat" }.scheduledWait)
        assertNull(sleeping.session("other")?.scheduledWait)
        assertNull(sleeping.session("detached")?.scheduledWait)
    }

    @Test
    fun `event waits survive delivery and clear when the last wake is removed`() {
        val timer = wake("timer", WakeCondition(deadline = now))
        val event = wake("event", WakeCondition(events = setOf(EventKey("custom.ready")), deadline = now))
        val state = AiStudioScreenState().withWorkspace(
            workspace,
            SchedulerState.Ready(listOf(timer, event), delivering = setOf(event.id)),
        )
        assertEquals(SessionWaitUi.WaitingForEvent, state.session("chat")?.scheduledWait)
        val remaining = state.withWorkspace(workspace, SchedulerState.Ready(listOf(timer)))
        assertEquals(SessionWaitUi.Sleeping, remaining.session("chat")?.scheduledWait)
        assertNull(remaining.withWorkspace(workspace, SchedulerState.Ready()).session("chat")?.scheduledWait)
        assertNull(remaining.withWorkspace(workspace, SchedulerState.Loading).session("chat")?.scheduledWait)
    }

    private fun wake(id: String, condition: WakeCondition) = ScheduledWake(
        WakeRequest(WakeId(id), ref, null, condition, "Continue", WakeOrigin.Feature("test")),
        now,
    )
}
