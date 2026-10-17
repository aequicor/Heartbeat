package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorKind
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorRef
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessEnd
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRejection
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WaitReason
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class HarnessScriptProcessesTest {
    private val issue = ProcessId("issue_7")
    private val planning = StageName("plan")
    private val helper = ExecutorRef(ExecutorKind.Helper, "helper-1")
    private val plan = ResultRef("report", "plan-1")
    private val answer = WakeCondition(events = setOf(EventKeys.custom("process.answer")))

    @Test
    fun `opening creates the process once and returns the stored record`() = runTest {
        val env = Env(this)
        val first = env.processes.open(issue, inputRevision = 3)
        assertEquals(ProcessRecord(issue, Instant.fromEpochSeconds(START), inputRevision = 3), first)
        env.clock.seconds = START + 60

        val again = env.processes.open(issue, inputRevision = 9)
        assertEquals(first, again)
        assertEquals(first, env.processes.get(issue))
        assertEquals(listOf(first), env.processes.list())
    }

    @Test
    fun `a missing process reads as absent and cannot be commanded`() = runTest {
        val env = Env(this)
        assertNull(env.processes.get(issue))
        val result = env.processes.apply(issue, ProcessCommand.ReviseInput(2))
        assertEquals(ProcessReduction.Rejected(ProcessRejection.UnknownProcess), result)
        assertTrue(env.processes.list().isEmpty())
        assertNull(env.data.read(issue.storageKey).value)
        assertNull(env.data.read("processes").value)
    }

    @Test
    fun `commands are persisted and an exact replay writes nothing`() = runTest {
        val env = Env(this)
        env.processes.open(issue)

        val started = env.processes.apply(issue, ProcessCommand.Start(planning, helper))
        val record = assertIs<ProcessReduction.Changed>(started).record
        assertIs<ProcessPhase.Running>(record.phase)
        assertEquals(record, env.processes.get(issue))

        val revision = env.data.read(issue.storageKey).revision
        val rejected = env.processes.apply(issue, ProcessCommand.Start(planning, helper))
        assertEquals(ProcessReduction.Rejected(ProcessRejection.AttemptInFlight), rejected)
        val finished = env.processes.apply(issue, ProcessCommand.Finish(1, AttemptOutcome.Succeeded(plan)))
        assertIs<ProcessReduction.Changed>(finished)
        val settled = env.data.read(issue.storageKey).revision
        assertEquals(revision + 1, settled)

        val replay = env.processes.apply(issue, ProcessCommand.Finish(1, AttemptOutcome.Succeeded(plan)))
        assertIs<ProcessReduction.Unchanged>(replay)
        assertEquals(settled, env.data.read(issue.storageKey).revision)
    }

    @Test
    fun `the stage the wait and the journal survive a restart`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val before = Env(this, secrets)
        before.processes.open(issue)
        before.processes.apply(issue, ProcessCommand.ObserveSubject(SubjectSha("a".repeat(40))))
        before.processes.apply(issue, ProcessCommand.Start(planning, helper))
        before.processes.apply(issue, ProcessCommand.Finish(1, AttemptOutcome.Succeeded(plan)))
        before.processes.apply(issue, ProcessCommand.Wait(WaitReason.Human, answer))
        before.processes.apply(issue, ProcessCommand.Grant(ProcessAuthority.Write))
        val key = WriteKey("comment_plan")
        val target = ResultRef("pull_request", "pr-7")
        before.processes.apply(issue, ProcessCommand.BeginWrite(key, ProcessAuthority.Write, target))

        val after = Env(this, secrets)
        val restored = checkNotNull(after.processes.get(issue))
        assertEquals(before.processes.get(issue), restored)
        assertEquals(planning, restored.stage)
        assertIs<ProcessPhase.Waiting>(restored.phase)
        assertEquals(plan, restored.currentResult(planning))
        assertEquals(listOf(key), restored.unresolvedWrites().map { it.key })

        val retry = after.processes.apply(issue, ProcessCommand.BeginWrite(key, ProcessAuthority.Write, target))
        assertEquals(ProcessReduction.Rejected(ProcessRejection.WriteUnresolved), retry)
    }

    @Test
    fun `concurrent commands never lose each other`() = runTest {
        val env = Env(this)
        env.processes.open(issue)
        val results = (1..CONCURRENT).map { revision ->
            async { env.processes.apply(issue, ProcessCommand.ReviseInput(revision.toLong())) }
        }.awaitAll()

        assertTrue(results.none { it is ProcessReduction.Rejected && it.reason != ProcessRejection.StaleRevision })
        assertEquals(CONCURRENT.toLong(), env.processes.get(issue)?.inputRevision)
    }

    @Test
    fun `list keeps the order of opening and skips an id without a record`() = runTest {
        val env = Env(this)
        val ids = listOf("b_two", "a_one", "c_three").map(::ProcessId)
        ids.forEach { env.processes.open(it) }
        assertEquals(ids, env.processes.list().map { it.id })

        val index = checkNotNull(env.data.read("processes").value)
        val withGhost = Json.parseToJsonElement(index.toString().replace("]", ",\"ghost\"]"))
        env.data.write("processes", withGhost, env.data.read("processes").revision)
        assertEquals(ids, env.processes.list().map { it.id })
        assertNull(env.processes.get(ProcessId("ghost")))
    }

    @Test
    fun `only so many processes can be stored at once`() = runTest {
        val env = Env(this)
        repeat(ProcessLimits.PROCESSES) { env.processes.open(ProcessId("p$it")) }
        assertFailsWith<IllegalArgumentException> { env.processes.open(ProcessId("extra")) }
        assertNull(env.processes.get(ProcessId("extra")))
        env.processes.open(ProcessId("p0"))

        env.processes.apply(ProcessId("p3"), ProcessCommand.Close(ProcessEnd.Completed))
        assertTrue(env.processes.remove(ProcessId("p3")))
        env.processes.open(ProcessId("extra"))
        assertEquals(ProcessLimits.PROCESSES, env.processes.list().size)
    }

    @Test
    fun `remove keeps a live process and one with an unresolved write`() = runTest {
        val env = Env(this)
        env.processes.open(issue)
        assertFalse(env.processes.remove(issue))
        env.processes.apply(issue, ProcessCommand.Grant(ProcessAuthority.Write))
        val key = WriteKey("comment_plan")
        env.processes.apply(
            issue,
            ProcessCommand.BeginWrite(key, ProcessAuthority.Write, ResultRef("pull_request", "7")),
        )
        env.processes.apply(issue, ProcessCommand.Close(ProcessEnd.Failed))
        assertFalse(env.processes.remove(issue))
        assertIs<ProcessPhase.Ended>(env.processes.get(issue)?.phase)

        env.processes.apply(issue, ProcessCommand.WriteNotApplied(key))
        assertTrue(env.processes.remove(issue))
        assertNull(env.processes.get(issue))
        assertTrue(env.processes.list().isEmpty())
        assertNull(env.data.read("processes").value)
        assertTrue(env.processes.remove(issue))
    }

    @Test
    fun `an unreadable record is reported without its content and never overwritten`() = runTest {
        val env = Env(this)
        env.processes.open(issue)
        val stored = checkNotNull(env.data.read(issue.storageKey).value)
        val newer = Json.parseToJsonElement(stored.toString().replace("\"v\":1", "\"v\":2,\"secret\":\"private\""))
        env.data.write(issue.storageKey, newer, env.data.read(issue.storageKey).revision)

        val failures = listOf(
            assertFailsWith<IllegalStateException> { env.processes.get(issue) },
            assertFailsWith<IllegalStateException> { env.processes.list() },
            assertFailsWith<IllegalStateException> { env.processes.apply(issue, ProcessCommand.ReviseInput(5)) },
            assertFailsWith<IllegalStateException> { env.processes.open(issue) },
            assertFailsWith<IllegalStateException> { env.processes.remove(issue) },
        )
        for (failure in failures) {
            assertFalse("private" in failure.message.orEmpty())
            assertNull(failure.cause)
        }
        assertEquals(newer, env.data.read(issue.storageKey).value)
    }

    @Test
    fun `processes of different harnesses are isolated`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val one = Env(this, secrets)
        val other = Env(this, secrets, HarnessId("other"))
        one.processes.open(issue)
        assertNull(other.processes.get(issue))
        assertTrue(other.processes.list().isEmpty())
    }

    @Test
    fun `a retired activation reaches no process`() = runTest {
        val env = Env(this)
        env.processes.open(issue)
        env.access.isActive = false
        assertFailsWith<IllegalStateException> { env.processes.get(issue) }
        assertFailsWith<IllegalStateException> { env.processes.apply(issue, ProcessCommand.ReviseInput(2)) }
        env.access.isActive = true
        assertEquals(issue, env.processes.get(issue)?.id)
    }

    private class Env(
        scope: TestScope,
        secrets: HarnessDataTestSecrets = HarnessDataTestSecrets(),
        owner: HarnessId = HarnessId("owner"),
    ) {
        val clock = TestClock(START)
        val access = ProcessTestAccess(scope)
        val data = HarnessScriptData(owner, access, ProtectedHarnessData(secrets))
        val processes = HarnessScriptProcesses(data, clock)
    }

    private class TestClock(var seconds: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(seconds)
    }

    private class ProcessTestAccess(scope: TestScope) : HarnessInstanceAccess {
        override val scope = scope.backgroundScope
        override val dispatcher = StandardTestDispatcher(scope.testScheduler)
        override var isActive = true
        override suspend fun awaitPublication() = isActive
    }

    private companion object {
        const val START = 1_000L
        const val CONCURRENT = 12
    }
}
