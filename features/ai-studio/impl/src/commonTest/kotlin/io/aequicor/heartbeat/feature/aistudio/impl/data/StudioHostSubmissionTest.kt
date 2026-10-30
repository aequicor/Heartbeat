package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRunAdmission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioHostSubmissionTest {
    @Test
    fun `legacy runtime refuses gated sends without invoking callbacks or the old run overload`() = runTest {
        var runs = 0
        var admissions = 0
        var accepted = 0
        val legacy = object : StudioRuntime {
            override val state = MutableStateFlow(StudioRuntimeState())
            override suspend fun defaults(): RunSettings = DefaultRunSettings
            override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome {
                runs++
                return RunOutcome.Completed
            }
            override suspend fun cancel(sessionId: String) = Unit
            override suspend fun respond(
                sessionId: String,
                requestId: String,
                optionId: String,
                answer: StudioPermissionAnswer?,
            ) = Unit
        }
        assertFailsWith<IllegalStateException> {
            legacy.run(
                "chat",
                "Generated answer",
                DefaultRunSettings,
                emptyList(),
                admission = StudioRunAdmission {
                    admissions++
                    true
                },
            ) { accepted++ }
        }
        assertEquals(0, runs)
        assertEquals(0, admissions)
        assertEquals(0, accepted)
        assertEquals(RunOutcome.Completed, legacy.run("chat", "Human prompt", DefaultRunSettings))
        assertEquals(1, runs)
    }

    @Test
    fun `caller cancellation revokes suspended preparation and suspended host admission`() = runTest {
        for (pauseAdmission in listOf(false, true)) {
            val events = mutableListOf<String>()
            val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
            val paused = CompletableDeferred<Unit>()
            val host = RunHost(events)
            host.execute = { request ->
                if (!pauseAdmission) paused.await()
                request.submission?.begin()
                events += "submitted"
                RunOutcome.Completed
            }
            val caller = async {
                val gate = StudioHostSubmission(
                    StudioRunAdmission {
                        events += "admission"
                        if (pauseAdmission) paused.await()
                        true
                    },
                    currentCoroutineContext(),
                )
                coordinator.run(host, runRequest("host").copy(submission = gate))
            }
            runCurrent()
            assertEquals(pauseAdmission, "admission" in events)
            caller.cancelAndJoin()
            runCurrent()
            paused.complete(Unit)
            runCurrent()
            assertFalse("submitted" in events)
            assertTrue("finished" in events)
            assertFalse(coordinator.isBusy("chat"))
        }
    }

    @Test
    fun `accepted host work retains profile ownership when its waiter is cancelled`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val finished = CompletableDeferred<Unit>()
        val host = RunHost(events)
        host.execute = { request ->
            request.submission?.begin()
            events += "submitted"
            finished.await()
            RunOutcome.Completed
        }
        val caller = async {
            val gate = StudioHostSubmission(StudioRunAdmission { true }, currentCoroutineContext())
            coordinator.run(host, runRequest("host").copy(submission = gate))
        }
        runCurrent()
        assertTrue("submitted" in events)
        caller.cancelAndJoin()
        assertFalse("finished" in events)
        assertTrue(coordinator.isBusy("chat"))
        finished.complete(Unit)
        runCurrent()
        assertTrue("finished" in events)
        assertFalse(coordinator.isBusy("chat"))
    }

    @Test
    fun `captured caller cancellation is checked even before coordinator starts waiting`() = runTest {
        val caller = Job()
        var admissions = 0
        val gate = StudioHostSubmission(
            StudioRunAdmission {
                admissions++
                true
            },
            caller,
        )
        caller.cancel()
        assertFailsWith<CancellationException> { gate.begin() }
        assertEquals(0, admissions)
    }

    @Test
    fun `helper prior host refusal retires its durable claim without submitting`() = runTest {
        val journal = StudioHelperAttempts(ChecklistTestStores())
        val helper = HelperId("helper")
        val request = runRequest("host")
        journal.prepare(helper, HelperPrompt(request.request, request.prompt))
        var admissions = 0
        val prior = StudioHostSubmission(
            StudioRunAdmission {
                admissions++
                false
            },
            currentCoroutineContext(),
        )
        val gate = StudioHelperSubmission(journal, emptyHelperAdmissions(), helper, request.request, prior)
        assertFailsWith<CancellationException> { gate.begin() }
        assertEquals(1, admissions)
        assertEquals(StudioHelperPhase.NotSubmitted, journal.receipt(helper, request.request)?.phase)
        assertTrue(prior.isCancelled)
    }
}
