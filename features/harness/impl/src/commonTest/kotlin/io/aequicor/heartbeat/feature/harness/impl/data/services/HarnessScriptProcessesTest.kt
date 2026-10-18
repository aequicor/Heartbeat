package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
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
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses.Companion.STORAGE_KEY
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessScriptProcessesTest {
    private val issue = ProcessId("issue_7")
    private val planning = StageName("plan")
    private val helper = ExecutorRef(ExecutorKind.Helper, "helper-1")
    private val plan = ResultRef("report", "plan-1")
    private val answer = WakeCondition(events = setOf(EventKeys.custom("process.answer")))

    @Test
    fun `opening creates the process once and returns the stored record`() = runTest {
        val env = ProcessesTestEnv(this)
        val first = env.processes.open(issue, inputRevision = 3)
        assertEquals(ProcessRecord(issue, Instant.fromEpochSeconds(PROCESS_TEST_START), inputRevision = 3), first)
        env.clock.seconds = PROCESS_TEST_START + 60

        val again = env.processes.open(issue, inputRevision = 9)
        assertEquals(first, again)
        assertEquals(first, env.processes.get(issue))
        assertEquals(listOf(first), env.processes.list())
    }

    @Test
    fun `a missing process reads as absent and cannot be commanded`() = runTest {
        val env = ProcessesTestEnv(this)
        assertNull(env.processes.get(issue))
        val result = env.processes.apply(issue, ProcessCommand.ReviseInput(2))
        assertEquals(ProcessReduction.Rejected(ProcessRejection.UnknownProcess), result)
        assertTrue(env.processes.list().isEmpty())
        assertNull(env.data.read(STORAGE_KEY).value)
    }

    @Test
    fun `commands are persisted and an exact replay writes nothing`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(issue)

        val started = env.processes.apply(issue, ProcessCommand.Start(planning, helper))
        val record = assertIs<ProcessReduction.Changed>(started).record
        assertIs<ProcessPhase.Running>(record.phase)
        assertEquals(record, env.processes.get(issue))

        val revision = env.data.read(STORAGE_KEY).revision
        val rejected = env.processes.apply(issue, ProcessCommand.Start(planning, helper))
        assertEquals(ProcessReduction.Rejected(ProcessRejection.AttemptInFlight), rejected)
        val finished = env.processes.apply(issue, ProcessCommand.Finish(1, AttemptOutcome.Succeeded(plan)))
        assertIs<ProcessReduction.Changed>(finished)
        val settled = env.data.read(STORAGE_KEY).revision
        assertEquals(revision + 1, settled)

        val replay = env.processes.apply(issue, ProcessCommand.Finish(1, AttemptOutcome.Succeeded(plan)))
        assertIs<ProcessReduction.Unchanged>(replay)
        assertEquals(settled, env.data.read(STORAGE_KEY).revision)
    }

    @Test
    fun `the stage the wait and the journal survive a restart`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val before = ProcessesTestEnv(this, secrets)
        before.processes.open(issue)
        before.processes.apply(issue, ProcessCommand.ObserveSubject(SubjectSha("a".repeat(40))))
        before.processes.apply(issue, ProcessCommand.Start(planning, helper))
        before.processes.apply(issue, ProcessCommand.Finish(1, AttemptOutcome.Succeeded(plan)))
        before.processes.apply(issue, ProcessCommand.Wait(WaitReason.Human, answer))
        before.processes.apply(issue, ProcessCommand.Grant(ProcessAuthority.Write))
        val key = WriteKey("comment_plan")
        val target = ResultRef("pull_request", "pr-7")
        before.processes.apply(issue, ProcessCommand.BeginWrite(key, ProcessAuthority.Write, target))

        val after = ProcessesTestEnv(this, secrets)
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
    fun `concurrent opens and commands never lose each other`() = runTest {
        val secrets = HarnessDataTestSecrets().apply {
            beforeRead = { yield() }
            beforeWrite = { yield() }
        }
        val env = ProcessesTestEnv(this, secrets)
        val ids = (1..CONCURRENT).map { ProcessId("p$it") }
        ids.map { async { env.processes.open(it) } }.awaitAll()
        assertEquals(ids.toSet(), env.processes.list().map { it.id }.toSet())

        env.processes.apply(ids.first(), ProcessCommand.Grant(ProcessAuthority.Write))
        val keys = (1..ProcessLimits.WRITES).map { WriteKey("w$it") }
        val results = keys.map { key ->
            async { env.processes.apply(ids.first(), ProcessCommand.BeginWrite(key, ProcessAuthority.Write, plan)) }
        }.awaitAll()

        assertTrue(results.all { it is ProcessReduction.Changed })
        assertEquals(keys.toSet(), env.processes.get(ids.first())?.writes?.map { it.key }?.toSet())
        assertEquals(ids.toSet(), env.processes.list().map { it.id }.toSet())
    }

    @Test
    fun `list keeps the order of opening`() = runTest {
        val env = ProcessesTestEnv(this)
        val ids = listOf("b_two", "a_one", "c_three").map(::ProcessId)
        ids.forEach { env.processes.open(it) }
        assertEquals(ids, env.processes.list().map { it.id })
        assertNull(env.processes.get(ProcessId("ghost")))
    }

    @Test
    fun `a removed process can be opened again as a fresh one`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(issue, inputRevision = 4)
        env.processes.apply(issue, ProcessCommand.Close(ProcessEnd.Completed))
        assertTrue(env.processes.remove(issue))

        val again = env.processes.open(issue, inputRevision = 1)
        assertEquals(1, again.inputRevision)
        assertEquals(ProcessPhase.Idle, again.phase)
        assertEquals(listOf(again), env.processes.list())
    }

    @Test
    fun `an activation revoked right after the write does not strand the process`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val env = ProcessesTestEnv(this, secrets)
        // The revocation lands after the one admitted write: nothing may be left half done by it.
        secrets.beforeWrite = { env.access.isActive = false }
        val opened = env.processes.open(issue)
        secrets.beforeWrite = {}
        env.access.isActive = true

        assertEquals(listOf(opened), env.processes.list())
        repeat(ProcessLimits.PROCESSES - 1) { env.processes.open(ProcessId("q$it")) }
        assertEquals(ProcessLimits.PROCESSES, env.processes.list().size)
    }

    @Test
    fun `a removal racing an open of the same process leaves one consistent answer`() = runTest {
        val secrets = HarnessDataTestSecrets().apply {
            beforeRead = { yield() }
            beforeWrite = { yield() }
        }
        val env = ProcessesTestEnv(this, secrets)
        env.processes.open(issue)
        env.processes.apply(issue, ProcessCommand.Close(ProcessEnd.Completed))

        val removal = async { env.processes.remove(issue) }
        val reopening = async { env.processes.open(issue) }
        removal.await()
        reopening.await()

        val listed = env.processes.list()
        assertEquals(env.processes.get(issue) != null, listed.any { it.id == issue })
        assertTrue(listed.size <= 1)
    }

    @Test
    fun `a stored set of another version with duplicates or too many processes is refused`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(issue)
        val stored = checkNotNull(env.data.read(STORAGE_KEY).value)
        val record = stored.jsonObject.getValue("records").jsonArray.single()
        fun withIds(vararg ids: String) = ids.map { Json.parseToJsonElement(record.toString().replace("issue_7", it)) }
        fun set(version: Int, records: List<JsonElement>) = JsonObject(
            mapOf("v" to JsonPrimitive(version), "records" to JsonArray(records)),
        )

        val refused = listOf(
            set(version = 2, records = listOf(record)),
            set(version = 1, records = listOf(record, record)),
            set(version = 1, records = withIds(*Array(ProcessLimits.PROCESSES + 1) { "p$it" })),
        )
        for (broken in refused) {
            env.data.write(STORAGE_KEY, broken, env.data.read(STORAGE_KEY).revision)
            val failure = assertFailsWith<IllegalStateException> { env.processes.list() }
            assertEquals("Stored process is unreadable", failure.message)
            assertNull(failure.cause)
            assertEquals(broken, env.data.read(STORAGE_KEY).value)
        }
    }

    @Test
    fun `a change that does not fit the namespace throws and leaves the stored set untouched`() = runTest {
        val env = ProcessesTestEnv(this)
        // Fill the namespace of this harness (a revision-one envelope with a single "filler" entry) so that exactly
        // FREE_BYTES remain: enough for the first process, not for a second one or a longer record.
        val overhead = """{"revision":1,"entries":{"filler":""}}""".length
        val filler = JsonPrimitive("x".repeat(HarnessLimits.DATA_BYTES - overhead - FREE_BYTES))
        assertIs<ScriptDataWriteResult.Written>(env.data.write("filler", filler, env.data.read("filler").revision))
        env.processes.open(issue)
        val before = env.data.read(STORAGE_KEY).value

        assertFailsWith<IllegalArgumentException> { env.processes.open(ProcessId("q1")) }
        assertFailsWith<IllegalArgumentException> { env.processes.apply(issue, ProcessCommand.Start(planning, helper)) }
        assertEquals(before, env.data.read(STORAGE_KEY).value)
        assertEquals(listOf(issue), env.processes.list().map { it.id })
    }

    @Test
    fun `only so many processes can be stored at once`() = runTest {
        val env = ProcessesTestEnv(this)
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
        val env = ProcessesTestEnv(this)
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

        env.processes.apply(issue, ProcessCommand.WriteNotApplied(key, 1))
        assertTrue(env.processes.remove(issue))
        assertNull(env.processes.get(issue))
        assertTrue(env.processes.list().isEmpty())
        assertNull(env.data.read(STORAGE_KEY).value)
        assertTrue(env.processes.remove(issue))
    }

    @Test
    fun `an unreadable record is reported without its content and never overwritten`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(issue)
        val stored = checkNotNull(env.data.read(STORAGE_KEY).value)
        val newer = Json.parseToJsonElement(stored.toString().replace("\"v\":1", "\"v\":2,\"secret\":\"private\""))
        env.data.write(STORAGE_KEY, newer, env.data.read(STORAGE_KEY).revision)

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
        assertEquals(newer, env.data.read(STORAGE_KEY).value)
    }

    @Test
    fun `processes of different harnesses are isolated`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val one = ProcessesTestEnv(this, secrets)
        val other = ProcessesTestEnv(this, secrets, HarnessId("other"))
        one.processes.open(issue)
        assertNull(other.processes.get(issue))
        assertTrue(other.processes.list().isEmpty())
    }

    @Test
    fun `a retired activation reaches no process`() = runTest {
        val env = ProcessesTestEnv(this)
        env.processes.open(issue)
        env.access.isActive = false
        assertFailsWith<IllegalStateException> { env.processes.get(issue) }
        assertFailsWith<IllegalStateException> { env.processes.apply(issue, ProcessCommand.ReviseInput(2)) }
        env.access.isActive = true
        assertEquals(issue, env.processes.get(issue)?.id)
    }

    private companion object {
        const val CONCURRENT = 6
        const val FREE_BYTES = 105
    }
}
