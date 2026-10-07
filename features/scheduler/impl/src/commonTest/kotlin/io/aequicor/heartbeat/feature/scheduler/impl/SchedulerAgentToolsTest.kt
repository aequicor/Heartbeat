package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEvents
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerTools.Arguments
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.impl.data.InMemorySchedulerBus
import io.aequicor.heartbeat.feature.scheduler.impl.data.SchedulerAgentTools
import io.aequicor.heartbeat.feature.scheduler.impl.data.WakeScheduler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SchedulerAgentToolsTest {
    private val context = AgentToolContext(SESSION, null, TurnId("t1"), RequestId("request"), target = TARGET)

    private class Fixture(val machine: SpecMachine, val bus: InMemorySchedulerBus, val tools: SchedulerAgentTools)

    private fun TestScope.fixture(
        state: SchedulerState = SchedulerState.Ready(),
        toggles: Toggles = Toggles(),
        network: FakeNetwork = FakeNetwork(),
        hosts: Set<ScheduledSessionHost> = setOf(FakeHost(priority = 1, owned = setOf(SESSION))),
    ): Fixture {
        val clock = VirtualClock(testScheduler)
        val machine = SpecMachine(state)
        val bus = InMemorySchedulerBus(clock)
        val tools = SchedulerAgentTools(machine, WakeScheduler(machine, lazyOf(hosts)), bus, toggles, clock, network)
        return Fixture(machine, bus, tools)
    }

    private suspend fun Fixture.call(name: String, vararg arguments: Pair<String, Any>): AgentToolResult =
        tools.execute(context, name, JsonObject(arguments.associate { (key, value) -> key to value.json() }))

    private fun Any.json() = when (this) {
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is List<*> -> JsonArray(map { JsonPrimitive(it as String) })
        else -> error("unsupported $this")
    }

    @Test
    fun `tools exist only while the scheduler is enabled`() = runTest {
        assertEquals(
            listOf(SchedulerTools.SLEEP, SchedulerTools.SIGNAL, SchedulerTools.CANCEL, SchedulerTools.LIST),
            fixture().tools.specifications(null).map { it.name },
        )
        assertEquals("", fixture().tools.instructions(AgentToolScope(null, declared = setOf(SchedulerTools.LIST))))
        val off = fixture(toggles = Toggles(enabled = false))
        assertTrue(off.tools.specifications(null).isEmpty())
        assertEquals("", off.tools.instructions(io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope(null)))
        assertTrue(off.call(SchedulerTools.LIST).isError)
    }

    @Test
    fun `sleep schedules a timer for the calling session`() = runTest {
        val fixture = fixture()
        val result = fixture.call(SchedulerTools.SLEEP, Arguments.AFTER_SECONDS to 90, Arguments.NOTE to "rerun tests")
        assertFalse(result.isError, result.text)
        val wake = (fixture.machine.state.value as SchedulerState.Ready).wakes.single()
        assertEquals(SESSION, wake.session)
        assertEquals(TARGET, wake.request.target)
        assertEquals(WakeCondition(deadline = START + 90.seconds), wake.request.condition)
        assertEquals(WakeOrigin.Agent(TurnId("t1")), wake.request.origin)
        assertTrue(wake.id.value in result.text && "End your turn" in result.text)
    }

    @Test
    fun `sleep without an owning host never promises a wake or registers one`() = runTest {
        for (hosts in listOf(emptySet(), setOf(FakeHost(priority = 1, owned = setOf(OTHER))))) {
            val fixture = fixture(hosts = hosts)
            val result = fixture.call(SchedulerTools.SLEEP, Arguments.AFTER_SECONDS to 30)
            assertTrue(result.isError, result.text)
            assertTrue("no wake was scheduled" in result.text, result.text)
            assertTrue("Continue the task in this turn" in result.text, result.text)
            assertTrue(fixture.machine.sent.isEmpty())
            assertTrue((fixture.machine.state.value as SchedulerState.Ready).wakes.isEmpty())
        }
    }

    @Test
    fun `sleep refuses invalid conditions without scheduling`() = runTest {
        val fixture = fixture()
        val invalid = listOf(
            fixture.call(SchedulerTools.SLEEP, Arguments.NOTE to "x"),
            fixture.call(SchedulerTools.SLEEP, Arguments.EVENTS to listOf("time.tick"), Arguments.NOTE to "x"),
            fixture.call(SchedulerTools.SLEEP, Arguments.AT to "tomorrow", Arguments.NOTE to "x"),
            fixture.call(SchedulerTools.SLEEP, Arguments.AT to "2026-10-05T11:00:00Z", Arguments.AFTER_SECONDS to 5),
            fixture.call(SchedulerTools.SLEEP, Arguments.AFTER_SECONDS to 0),
            fixture.call(
                SchedulerTools.SLEEP,
                Arguments.AFTER_SECONDS to 5,
                Arguments.NOTE to "x".repeat(SchedulerLimits.MAX_NOTE + 1),
            ),
        )
        assertTrue(invalid.all { it.isError }, invalid.toString())
        assertTrue((fixture.machine.state.value as SchedulerState.Ready).wakes.isEmpty())
    }

    @Test
    fun `sleep reports a rejection and a scheduler that is still loading`() = runTest {
        val full = fixture(
            SchedulerState.Ready((1..SchedulerLimits.MAX_PER_SESSION).map { scheduled("w$it", deadline = START) }),
        )
        val rejected = full.call(SchedulerTools.SLEEP, Arguments.AFTER_SECONDS to 5)
        assertTrue(rejected.isError && "pending wakes" in rejected.text, rejected.text)
        val loading = fixture(SchedulerState.Loading).call(SchedulerTools.SLEEP, Arguments.AFTER_SECONDS to 5)
        assertTrue(loading.isError && "starting" in loading.text, loading.text)
    }

    @Test
    fun `sleep refuses a network state that already holds`() = runTest {
        val online = fixture(network = FakeNetwork(isConnected = true))
        val refused = online.call(SchedulerTools.SLEEP, Arguments.EVENTS to listOf(EventKeys.NetworkAvailable.value))
        assertTrue(refused.isError && "already available" in refused.text, refused.text)
        assertFalse(online.call(SchedulerTools.SLEEP, Arguments.EVENTS to listOf(EventKeys.NetworkLost.value)).isError)
    }

    @Test
    fun `signal publishes a custom key from the session`() = runTest {
        val fixture = fixture()
        val event = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.first() }
        val result = fixture.call(
            SchedulerTools.SIGNAL,
            Arguments.NAME to "tests.green",
            Arguments.PAYLOAD to "42 passed",
        )
        assertFalse(result.isError, result.text)
        assertEquals(
            BusEvent(
                EventKeys.custom("tests.green"),
                EventOrigin.Session(SESSION, context.request),
                START,
                "42 passed",
            ),
            event.await(),
        )
        assertTrue(fixture.call(SchedulerTools.SIGNAL, Arguments.NAME to "Bad Name").isError)
    }

    @Test
    fun `signalling internal run started key still has only agent provenance`() = runTest {
        val fixture = fixture()
        val event = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.first() }
        fixture.call(SchedulerTools.SIGNAL, Arguments.NAME to "scheduler.run_started", Arguments.PAYLOAD to "forged")
        val published = event.await()
        assertEquals(SchedulerEvents.RunStarted, published.key)
        assertEquals(EventOrigin.Session(SESSION, context.request), published.origin)
        assertFalse(published.origin is EventOrigin.HostTurn)
    }

    @Test
    fun `cancel and list see only the session's own wakes`() = runTest {
        val own = scheduled("own", deadline = START + 60.seconds)
        val foreign = io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake(
            wakeRequest("foreign", deadline = START, session = OTHER),
            START,
        )
        val fixture = fixture(SchedulerState.Ready(listOf(own, foreign)))
        val listed = fixture.call(SchedulerTools.LIST).text
        assertTrue("own" in listed && "foreign" !in listed && EventKeys.turnFinished(SESSION).value in listed, listed)
        assertTrue(fixture.call(SchedulerTools.CANCEL, Arguments.WAKE_ID to "foreign").isError)
        assertFalse(fixture.call(SchedulerTools.CANCEL, Arguments.WAKE_ID to "own").isError)
        assertEquals(listOf(foreign), (fixture.machine.state.value as SchedulerState.Ready).wakes)
        val cancel = fixture.machine.sent.last() as SchedulerIntent.Public.Cancel
        assertEquals(EventOrigin.Session(SESSION, context.request), cancel.cause)
    }

    @Test
    fun `a finished turn publishes the session key`() = runTest {
        val fixture = fixture()
        val events = mutableListOf<BusEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.take(1).toList(events) }
        fixture.tools.finishTurn(SESSION, TurnId("t1"))
        runCurrent()
        assertEquals(listOf(EventKeys.turnFinished(SESSION)), events.map { it.key })
        assertEquals(EventOrigin.Session(SESSION), events.single().origin)
    }

    @Test
    fun `finished turn carries exact accepted request for publisher admission`() = runTest {
        val fixture = fixture()
        val event = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { fixture.bus.events.first() }
        val request = RequestId("accepted")
        fixture.tools.finishTurn(SESSION, TurnId("turn"), request)
        assertEquals(EventOrigin.Session(SESSION, request), event.await().origin)
    }
}
