package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentAvailability
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentRequest
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStart
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStatus
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentWake
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.impl.data.ProfileBackgroundAgents
import io.aequicor.heartbeat.feature.scheduler.impl.data.WakeScheduler
import io.aequicor.heartbeat.feature.scheduler.impl.data.finished
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BackgroundAgentsTest {
    private val id = ActionId("sa1")
    private val reviewer = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("strong"))
    private val request = BackgroundAgentRequest(
        id,
        OWNER,
        SESSION,
        PROJECT,
        TARGET,
        "Helper",
        "Private task",
        "Hand back a summary",
        HelperModelOptions("low", reviewer),
    )

    @Test
    fun `a start returns before acceptance and the result stays until its owner acknowledges it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = HelperHostFake().apply { beforePrompt = { gate.await() } }
        val f = agents(this, host)
        assertEquals(BackgroundAgentStart.Started(id), f.agents.start(request))
        runCurrent()
        assertEquals(BackgroundAgentStatus.Running, f.agents.status(id, OWNER))
        assertEquals(HelperModelOptions("low", reviewer), host.creations.single().model)
        assertEquals(listOf(id), f.agents.retained(OWNER))
        assertTrue(f.agents.retained("other").isEmpty())
        gate.complete(Unit)
        host.results[RequestId("action_sa1")] = HelperResult(RequestId("action_sa1"), HelperOutcome.Completed, "Done")
        f.fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertEquals(BackgroundAgentStatus.Finished(isCompleted = true, text = "Done"), f.agents.status(id, OWNER))
        assertEquals(BackgroundAgentStatus.Unknown, f.agents.status(id, "other"))
        f.agents.acknowledge(id, "other")
        assertEquals(listOf(id), f.fixture.journal.records.map { it.id })
        f.agents.acknowledge(id, OWNER)
        assertTrue(f.fixture.journal.records.isEmpty())
        assertEquals(BackgroundAgentStatus.Unknown, f.agents.status(id, OWNER))
    }

    @Test
    fun `turned off actions and helper parents refuse without journaling`() = runTest {
        val off = agents(this, HelperHostFake(), Toggles(enabled = false))
        assertEquals(BackgroundAgentAvailability.Disabled, off.agents.availability(SESSION))
        assertTrue(off.agents.start(request) is BackgroundAgentStart.Refused)
        assertTrue(off.fixture.journal.records.isEmpty())
        val on = agents(this, HelperHostFake())
        assertEquals(BackgroundAgentAvailability.Available, on.agents.availability(SESSION))
        assertTrue(on.agents.start(request) is BackgroundAgentStart.Started)
        runCurrent()
        assertEquals(BackgroundAgentAvailability.HelperParent, on.agents.availability(OTHER))
        val nested = on.agents.start(request.copy(id = ActionId("sa2"), parent = OTHER))
        assertTrue(nested is BackgroundAgentStart.Refused)
    }

    @Test
    fun `helper payloads read as a final message or the status that ended them`() {
        assertEquals(
            BackgroundAgentStatus.Finished(isCompleted = true, text = "line one\nline two"),
            finished("status: finished\nfinal message:\nline one\nline two"),
        )
        assertEquals(
            BackgroundAgentStatus.Finished(isCompleted = false, text = "failed\nPartial"),
            finished("status: failed\nfinal message:\nPartial"),
        )
        assertEquals(
            BackgroundAgentStatus.Finished(isCompleted = false, text = "interrupted (the app closed)"),
            finished("status: interrupted (the app closed)"),
        )
    }

    @Test
    fun `a wake is confirmed only after the scheduler state is durable`() = runTest {
        val f = agents(this, HelperHostFake())
        val wake = wakeRequest("wait", deadline = START + 60.seconds)
        val scheduled = async { f.agents.schedule(wake) }
        runCurrent()
        assertFalse(scheduled.isCompleted)
        f.fixture.persistWakes()
        assertEquals(BackgroundAgentWake.Scheduled, scheduled.await())
        assertEquals(listOf(wake), f.fixture.wakeStorage.wakes.map { it.request })
    }

    @Test
    fun `a handled quota rejection is not a confirmed wake`() = runTest {
        val existing = List(SchedulerLimits.MAX_PER_SESSION) { scheduled("existing$it", deadline = START + 60.seconds) }
        val machine = SpecMachine(SchedulerState.Ready(existing))
        val f = agents(this, HelperHostFake(), machine = machine)
        val outcome = f.agents.schedule(wakeRequest("wait", deadline = START + 60.seconds))
        assertTrue(assertIs<BackgroundAgentWake.Refused>(outcome).reason.contains("pending wakes"))
        assertEquals(existing, (machine.state.value as SchedulerState.Ready).wakes)
        assertTrue(machine.effects.isEmpty())
    }

    @Test
    fun `an unconfirmed durable write and disabled scheduling never confirm a wake`() = runTest {
        val f = agents(this, HelperHostFake())
        val wake = wakeRequest("wait", deadline = START + 60.seconds)
        val outcome = f.agents.schedule(wake)
        assertTrue(assertIs<BackgroundAgentWake.Refused>(outcome).reason.contains("not confirmed"))
        assertTrue(f.fixture.wakeStorage.wakes.isEmpty())
        val off = agents(this, HelperHostFake(), Toggles(enabled = false))
        assertIs<BackgroundAgentWake.Refused>(off.agents.schedule(wake))
        assertTrue(off.fixture.machine.sent.isEmpty())
    }

    private fun agents(
        scope: TestScope,
        host: HelperHostFake,
        toggles: Toggles = Toggles(),
        machine: SpecMachine = SpecMachine(),
    ): AgentsFixture {
        val fixture = ActionsFixture(scope, machine, hosts = setOf(host), toggles = toggles)
        val agents = ProfileBackgroundAgents(
            lazyOf(fixture.actions),
            lazyOf(fixture.helperAgents),
            fixture.results,
            toggles,
            lazyOf(WakeScheduler(machine, lazyOf(setOf(host)), fixture.persistence)),
            fixture.clock,
        )
        return AgentsFixture(fixture, agents)
    }

    private class AgentsFixture(val fixture: ActionsFixture, val agents: ProfileBackgroundAgents)

    private companion object {
        const val OWNER = "adaptive_subagents"
    }
}
