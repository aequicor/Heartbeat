package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioDefaults
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AiStudioEffectsTest {
    @Test
    fun `load reports the toggle and the default project`() = runTest {
        val fixture = Fixture(this, agent = { flowOf() }, isEnabled = false)
        fixture.effects.handle(AiStudioEffect.Load, fixture.machine)
        assertEquals(
            listOf<AiStudioIntent>(
                AiStudioIntent.Internal.Loaded(false, StudioDefaults("p-heartbeat", DefaultRunSettings)),
            ),
            fixture.machine.sent,
        )
    }

    @Test
    fun `created session is titled by the first prompt line`() = runTest {
        val fixture = Fixture(this, agent = { flowOf() })
        val prompt = "Design the engine facade\nwith details"
        fixture.effects.handle(
            AiStudioEffect.CreateSession(3, "p-heartbeat", prompt, DefaultRunSettings),
            fixture.machine,
        )
        val created = assertIs<AiStudioIntent.Internal.SessionCreated>(fixture.machine.sent.single())
        assertEquals(3, created.paneId)
        val session = fixture.repository.observeWorkspace().first().session(created.sessionId)
        assertEquals("Design the engine facade", session?.title)
        assertEquals("p-heartbeat", session?.projectId)
    }

    @Test
    fun `a completed run records the prompt, the streamed tools and the branch`() = runTest {
        val fixture = Fixture(this, agent = {
            flowOf(
                AgentEvent.Text("Plan "),
                AgentEvent.ToolStarted("t", "Running"),
                AgentEvent.ToolOutput("t", "ok\n"),
                AgentEvent.ToolFinished("t", "Done", isSuccess = true, diff = "+line"),
                AgentEvent.BranchCreated("studio/plan"),
                AgentEvent.Text("ready"),
            )
        })
        fixture.effects.handle(AiStudioEffect.Run("s-greeting", "Next", DefaultRunSettings), fixture.machine)

        assertEquals(
            listOf<AiStudioIntent>(AiStudioIntent.Internal.RunFinished("s-greeting", RunOutcome.Completed)),
            fixture.machine.sent,
        )
        val transcript = fixture.repository.observeMessages("s-greeting").first()
        assertEquals("Next", assertIs<StudioMessage.Prompt>(transcript[transcript.lastIndex - 1]).text)
        val reply = assertIs<StudioMessage.Reply>(transcript.last())
        assertEquals("Plan ready", reply.text)
        assertEquals("Plan ", assertIs<StudioReplyPart.Text>(reply.parts[0]).text)
        assertEquals(reply.tools.single(), assertIs<StudioReplyPart.Tool>(reply.parts[1]).tool)
        assertEquals("ready", assertIs<StudioReplyPart.Text>(reply.parts[2]).text)
        assertFalse(reply.isStreaming)
        assertEquals(StudioToolRun("t", "Done", ToolRunStatus.Done, "ok\n", "+line"), reply.tools.single())
        assertEquals("studio/plan", fixture.repository.observeWorkspace().first().session("s-greeting")?.branch)
    }

    @Test
    fun `a stop request ends only its own run and notes the elapsed time`() = runTest {
        val fixture = Fixture(this, agent = {
            flow {
                emit(AgentEvent.ToolStarted("t", "Running"))
                awaitCancellation()
            }
        })
        val run = launch {
            fixture.effects.handle(AiStudioEffect.Run("s-greeting", "Long task", DefaultRunSettings), fixture.machine)
        }
        runCurrent()
        fixture.effects.handle(AiStudioEffect.Cancel("s-build"), fixture.machine)
        fixture.clock.advance(9.seconds)
        runCurrent()
        assertTrue(run.isActive)

        fixture.effects.handle(AiStudioEffect.Cancel("s-greeting"), fixture.machine)
        run.join()

        assertEquals(
            listOf<AiStudioIntent>(AiStudioIntent.Internal.RunFinished("s-greeting", RunOutcome.Stopped)),
            fixture.machine.sent,
        )
        val transcript = fixture.repository.observeMessages("s-greeting").first()
        assertEquals(9.seconds, assertIs<StudioMessage.Stopped>(transcript.last()).elapsed)
        val reply = assertIs<StudioMessage.Reply>(transcript[transcript.lastIndex - 1])
        assertFalse(reply.isStreaming)
        assertEquals(ToolRunStatus.Failed, reply.tools.single().status)
    }

    @Test
    fun `an agent failure finishes the run as failed and closes the reply`() = runTest {
        val fixture = Fixture(this, agent = {
            flow {
                emit(AgentEvent.Text("Partial"))
                delay(10.milliseconds)
                error("provider unavailable")
            }
        })
        fixture.effects.handle(AiStudioEffect.Run("s-greeting", "Task", DefaultRunSettings), fixture.machine)

        assertEquals(
            listOf<AiStudioIntent>(AiStudioIntent.Internal.RunFinished("s-greeting", RunOutcome.Failed)),
            fixture.machine.sent,
        )
        val transcript = fixture.repository.observeMessages("s-greeting").first()
        assertIs<StudioMessage.Failed>(transcript.last())
        assertFalse(assertIs<StudioMessage.Reply>(transcript[transcript.lastIndex - 1]).isStreaming)
    }

    @Test
    fun `leaving the studio cancels the run without leaving a streaming reply`() = runTest {
        val fixture = Fixture(this, agent = { flow { awaitCancellation() } })
        val run = launch {
            fixture.effects.handle(AiStudioEffect.Run("s-greeting", "Task", DefaultRunSettings), fixture.machine)
        }
        runCurrent()
        run.cancel()
        run.join()
        val reply = fixture.repository.observeMessages("s-greeting").first().last()
        assertFalse(assertIs<StudioMessage.Reply>(reply).isStreaming)
        assertTrue(fixture.machine.sent.isEmpty())
    }

    @Test
    fun `edits are applied to the session`() = runTest {
        val fixture = Fixture(this, agent = { flowOf() })
        fixture.effects.handle(AiStudioEffect.Apply("s-build", SessionEdit.SetPinned(true)), fixture.machine)
        fixture.effects.handle(AiStudioEffect.Apply("s-build", SessionEdit.Rename("Faster build")), fixture.machine)
        val session = fixture.repository.observeWorkspace().first().session("s-build")
        assertEquals("Faster build", session?.title)
        assertTrue(session?.isPinned == true)
    }

    @Test
    fun `a stop sent before the stream starts is kept until the run sees it`() = runTest {
        val fixture = Fixture(this, agent = { flow { awaitCancellation() } })
        val run = launch {
            fixture.effects.handle(AiStudioEffect.Run("s-greeting", "Task", DefaultRunSettings), fixture.machine)
        }
        launch { fixture.effects.handle(AiStudioEffect.Cancel("s-greeting"), fixture.machine) }
        run.join()
        assertEquals(
            listOf<AiStudioIntent>(AiStudioIntent.Internal.RunFinished("s-greeting", RunOutcome.Stopped)),
            fixture.machine.sent,
        )
    }

    @Test
    fun `a stop arriving after its run ended does not stop the next run`() = runTest {
        val fixture = Fixture(this, agent = { flowOf(AgentEvent.Text("Done")) })
        fixture.effects.handle(AiStudioEffect.Run("s-greeting", "First", DefaultRunSettings), fixture.machine)
        fixture.effects.handle(AiStudioEffect.Cancel("s-greeting"), fixture.machine)
        fixture.effects.handle(AiStudioEffect.Run("s-greeting", "Second", DefaultRunSettings), fixture.machine)
        assertEquals(
            List<AiStudioIntent>(2) { AiStudioIntent.Internal.RunFinished("s-greeting", RunOutcome.Completed) },
            fixture.machine.sent,
        )
    }

    @Test
    fun `availability changes are reported while observed`() = runTest {
        val fixture = Fixture(this, agent = { flowOf() })
        val observation = launch { fixture.effects.handle(AiStudioEffect.ObserveAvailability, fixture.machine) }
        runCurrent()
        fixture.availability.value = false
        runCurrent()
        observation.cancel()
        assertEquals(
            listOf<AiStudioIntent>(
                AiStudioIntent.Internal.AvailabilityChanged(true),
                AiStudioIntent.Internal.AvailabilityChanged(false),
            ),
            fixture.machine.sent,
        )
    }

    @Test
    fun `long prompts are shortened for titles`() {
        assertEquals("a".repeat(60) + "…", titleOf("a".repeat(80)))
        assertEquals("Short", titleOf("  Short  \nsecond line"))
    }

    private class Fixture(scope: TestScope, agent: StudioAgent, isEnabled: Boolean = true) {
        val clock = TestClock(scope)
        val repository = InMemoryStudioRepository(clock)
        val machine = RecordingMachine()
        val availability = MutableStateFlow(isEnabled)
        val effects = AiStudioEffects(
            repository,
            agent,
            object : StudioAvailability {
                override suspend fun isEnabled(): Boolean = availability.value

                override fun observe(): Flow<Boolean> = availability
            },
            clock,
        )
    }
}

/** Wall clock that follows the virtual time of the test scheduler. */
internal class TestClock(private val scope: TestScope) : Clock {
    fun advance(duration: Duration) = scope.advanceTimeBy(duration)

    override fun now(): Instant = Instant.fromEpochMilliseconds(START + scope.testScheduler.currentTime)

    private companion object {
        const val START = 1_790_000_000_000L
    }
}

internal class RecordingMachine : EffectScope<AiStudioIntent> {
    val sent = mutableListOf<AiStudioIntent>()

    override suspend fun send(intent: AiStudioIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}
