package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProcessRecordSerializationTest {
    private val answer = WakeCondition(
        events = setOf(EventKey("custom.process.answer")),
        deadline = processAt(3_600),
    )

    @Test
    fun `every phase survives a roundtrip in every json configuration`() {
        val busy = newProcess().attempt(planStage, succeeded("plan-1"))
        val records = listOf(
            newProcess(),
            busy.change(ProcessCommand.Start(buildStage, ExecutorRef(ExecutorKind.Organism, "cell-7"))),
            busy.change(ProcessCommand.Wait(WaitReason.Human, answer)),
            busy.attempt(buildStage, failed(AttemptFailure.Interrupted))
                .change(ProcessCommand.ObserveSubject(sha('c')))
                .change(ProcessCommand.ReviseInput(4))
                .change(ProcessCommand.Close(ProcessEnd.Completed)),
            journaled(),
        )
        for (format in formats()) {
            for (record in records) {
                assertEquals(record, format.decodeFromString<ProcessRecord>(format.encodeToString(record)))
            }
        }
    }

    @Test
    fun `stored names are stable`() {
        val record = newProcess()
            .attempt(planStage, succeeded("plan-1"))
            .change(ProcessCommand.Wait(WaitReason.External, answer))
        val json = Json.parseToJsonElement(Json.encodeToString(record)).jsonObject

        assertEquals("waiting", json.getValue("phase").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("external", waitReason(json))
        val closed = json.getValue("history").toString()
        assertTrue("\"type\":\"succeeded\"" in closed, closed)
        assertTrue("\"kind\":\"helper\"" in closed, closed)
    }

    @Test
    fun `stored names of grants and writes are stable`() {
        val json = Json.parseToJsonElement(Json.encodeToString(journaled())).jsonObject

        assertEquals(
            setOf("write", "merge"),
            json.getValue("grants").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val phases = json.getValue("writes").jsonArray.map {
            it.jsonObject.getValue("phase").jsonObject.getValue("type").jsonPrimitive.content
        }
        assertEquals(listOf("confirmed", "unknown", "intended", "not_applied"), phases)
        assertEquals(
            listOf("write", "merge", "write", "merge"),
            json.getValue("writes").jsonArray.map { it.jsonObject.getValue("authority").jsonPrimitive.content },
        )
    }

    @Test
    fun `a record stored with only its required fields loads with defaults`() {
        val minimal = Json.parseToJsonElement(Json.encodeToString(newProcess())).jsonObject
        assertEquals(setOf("id", "createdAt"), minimal.keys)
        assertEquals(newProcess(), Json.decodeFromJsonElement<ProcessRecord>(minimal))
    }

    @Test
    fun `a record with a field from a later version still loads`() {
        val record = newProcess().change(ProcessCommand.ReviseInput(2))
        val stored = Json.parseToJsonElement(Json.encodeToString(record)).jsonObject
        val later = JsonObject(stored + ("futureField" to Json.parseToJsonElement("{\"x\":1}")))
        val tolerant = Json { ignoreUnknownKeys = true }
        assertEquals(record, tolerant.decodeFromJsonElement<ProcessRecord>(later))
    }

    @Test
    fun `write tries are never reused even after the journal forgot their key`() {
        val journal = journaled()
        assertEquals(5, journal.nextWriteTry)
        assertFalse("nextWriteTry" in Json.encodeToJsonElement(ProcessRecord.serializer(), journal).jsonObject)

        val target = ResultRef("pull_request", "pr-7")
        var forgetful = newProcess().change(ProcessCommand.Grant(ProcessAuthority.Write))
        repeat(ProcessLimits.WRITES + 1) { n ->
            val key = WriteKey("w$n")
            forgetful = forgetful.change(ProcessCommand.BeginWrite(key, ProcessAuthority.Write, target))
                .change(ProcessCommand.ConfirmWrite(key, ResultRef("comment", "c-1")))
        }
        assertEquals(ProcessLimits.WRITES + 2, forgetful.nextWriteTry)
        assertEquals(forgetful, roundtrip(forgetful))
        assertEquals(ProcessLimits.WRITES + 2, roundtrip(forgetful).nextWriteTry)
    }

    private fun roundtrip(record: ProcessRecord): ProcessRecord =
        Json.decodeFromString(ProcessRecord.serializer(), Json.encodeToString(ProcessRecord.serializer(), record))

    @Test
    fun `inconsistent records are refused when constructed`() {
        val ran = newProcess().attempt(planStage, succeeded("plan-1"))
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        val attempt = assertIs<ProcessPhase.Running>(running.phase).attempt
        val future = ran.history.map { it.copy(attempt = it.attempt.copy(inputRevision = 1)) }

        assertFailsWith<IllegalArgumentException> { ran.copy(nextAttempt = 0) }
        assertFailsWith<IllegalArgumentException> { ran.copy(nextAttempt = 1) }
        assertFailsWith<IllegalArgumentException> { ran.copy(inputRevision = -1) }
        assertFailsWith<IllegalArgumentException> { ran.copy(updatedAt = processStart - 1.seconds) }
        assertFailsWith<IllegalArgumentException> { ran.copy(history = ran.history + ran.history) }
        assertFailsWith<IllegalArgumentException> { ran.copy(history = future) }
        assertFailsWith<IllegalArgumentException> { running.copy(nextAttempt = 5) }
        assertFailsWith<IllegalArgumentException> {
            running.copy(phase = ProcessPhase.Running(attempt.copy(number = 2)))
        }
        assertFailsWith<IllegalArgumentException> {
            running.copy(phase = ProcessPhase.Running(attempt.copy(inputRevision = 9)))
        }
    }

    @Test
    fun `inconsistent waits and write journals are refused when constructed`() {
        val waiting = newProcess().change(ProcessCommand.Wait(WaitReason.Human, answer))
        val wait = assertIs<ProcessPhase.Waiting>(waiting.phase).wait
        val journal = journaled()
        val entry = journal.writes.first()

        assertFailsWith<IllegalArgumentException> { waiting.copy(nextWait = 0) }
        assertFailsWith<IllegalArgumentException> { waiting.copy(nextWait = 5) }
        assertFailsWith<IllegalArgumentException> {
            waiting.copy(phase = ProcessPhase.Waiting(wait.copy(number = 2)))
        }
        assertFailsWith<IllegalArgumentException> { wait.copy(number = 0) }
        assertFailsWith<IllegalArgumentException> { journal.copy(writes = journal.writes + entry) }
        assertFailsWith<IllegalArgumentException> { entry.copy(tries = 0) }
        assertFailsWith<IllegalArgumentException> {
            journal.copy(writes = (0..ProcessLimits.WRITES).map { entry.copy(key = WriteKey("w$it")) })
        }
    }

    @Test
    fun `an inconsistent stored record is refused when decoded`() {
        val ran = newProcess().attempt(planStage, succeeded("plan-1"))
        val stored = Json.parseToJsonElement(Json.encodeToString(ran)).jsonObject
        val rewound = JsonObject(stored + ("nextAttempt" to Json.parseToJsonElement("1")))
        assertFailsWith<IllegalArgumentException> { Json.decodeFromJsonElement<ProcessRecord>(rewound) }
    }

    @Test
    fun `no field of a record prints private values`() {
        val person = ExecutorRef(ExecutorKind.Human, "secret-person")
        val record = newProcess()
            .attempt(planStage, succeeded("secret-result"), person)
            .change(ProcessCommand.Start(buildStage, person))
        assertFalse("secret" in record.toString())
        assertFalse("secret" in record.history.toString())
        assertFalse("secret" in record.phase.toString())
    }

    @Test
    fun `a fully loaded running record fits the record budget`() {
        val running = fullyLoaded().change(ProcessCommand.Start(stage(0), maxExecutor()), seconds = 1_000)
        val bytes = Json.encodeToString(running).encodeToByteArray().size
        assertTrue(bytes <= ProcessLimits.RECORD_BYTES, "running record takes $bytes bytes")
    }

    @Test
    fun `a fully loaded waiting record fits the record budget`() {
        val events = (0 until MAX_EVENTS).map {
            EventKey(
                "custom." + "e".repeat(LONG_EVENT_BODY) + it.toString().padStart(2, '0'),
            )
        }
        val wait = ProcessCommand.Wait(WaitReason.External, WakeCondition(events.toSet(), processAt(1)))
        val bytes = Json.encodeToString(fullyLoaded().change(wait, seconds = 1_000)).encodeToByteArray().size
        assertTrue(bytes <= ProcessLimits.RECORD_BYTES, "waiting record takes $bytes bytes")
    }

    private fun fullyLoaded(): ProcessRecord {
        var process = newProcess()
            .change(ProcessCommand.ReviseInput(Long.MAX_VALUE))
            .change(ProcessCommand.ObserveSubject(SubjectSha("f".repeat(SHA256_CHARS))))
        repeat(ProcessLimits.HISTORY + 1) { n ->
            val result = ResultRef(
                "k".repeat(ProcessLimits.STAGE_CHARS),
                "r".repeat(ProcessLimits.REF_CHARS - 2) + n.toString().padStart(2, '0'),
            )
            process = process.attempt(
                stage = stage(n % ProcessLimits.STAGES),
                outcome = AttemptOutcome.Succeeded(result),
                executor = maxExecutor(),
                seconds = 10L * (n + 1),
            )
        }
        assertEquals(ProcessLimits.HISTORY, process.history.size)
        return process.withFullJournal()
    }

    /** Four authorities granted and as many journaled writes as fit, each field at its maximum length. */
    private fun ProcessRecord.withFullJournal(): ProcessRecord {
        var process = ProcessAuthority.entries.fold(this) { acc, authority ->
            acc.change(ProcessCommand.Grant(authority))
        }
        repeat(ProcessLimits.WRITES) { n ->
            val key = WriteKey("k".repeat(ProcessLimits.WRITE_KEY_CHARS - 2) + n.toString().padStart(2, '0'))
            val target = ResultRef("t".repeat(ProcessLimits.STAGE_CHARS), "p".repeat(ProcessLimits.REF_CHARS))
            val evidence = ResultRef("e".repeat(ProcessLimits.STAGE_CHARS), "q".repeat(ProcessLimits.REF_CHARS))
            process = process
                .change(ProcessCommand.BeginWrite(key, ProcessAuthority.Release, target), seconds = 2_000L + n)
                .change(ProcessCommand.ConfirmWrite(key, evidence), seconds = 2_000L + n)
        }
        assertEquals(ProcessLimits.WRITES, process.writes.size)
        return process
    }

    /** One write in each phase, two authorities granted. */
    private fun journaled(): ProcessRecord {
        val target = ResultRef("pull_request", "pr-7")
        fun begin(key: String, authority: ProcessAuthority) =
            ProcessCommand.BeginWrite(WriteKey(key), authority, target)
        return newProcess()
            .change(ProcessCommand.Grant(ProcessAuthority.Write))
            .change(ProcessCommand.Grant(ProcessAuthority.Merge))
            .change(begin("confirmed_one", ProcessAuthority.Write))
            .change(ProcessCommand.ConfirmWrite(WriteKey("confirmed_one"), ResultRef("comment", "c-1")))
            .change(begin("unknown_one", ProcessAuthority.Merge))
            .change(ProcessCommand.MarkWriteUnknown(WriteKey("unknown_one"), 2))
            .change(begin("intended_one", ProcessAuthority.Write))
            .change(begin("failed_one", ProcessAuthority.Merge))
            .change(ProcessCommand.WriteNotApplied(WriteKey("failed_one"), 4))
    }

    private fun stage(index: Int): StageName =
        StageName("s".repeat(ProcessLimits.STAGE_CHARS - 2) + index.toString().padStart(2, '0'))

    private fun maxExecutor(): ExecutorRef = ExecutorRef(ExecutorKind.Organism, "x".repeat(ProcessLimits.REF_CHARS))

    private fun waitReason(record: JsonObject): String =
        record.getValue("phase").jsonObject.getValue("wait").jsonObject.getValue("reason").jsonPrimitive.content

    private fun formats(): List<Json> = listOf(
        Json,
        Json { encodeDefaults = true },
        Json { explicitNulls = false },
        Json {
            encodeDefaults = true
            explicitNulls = false
        },
    )

    private companion object {
        private const val MAX_EVENTS = 16
        private const val SHA256_CHARS = 64

        /** "custom." (7) + body + two digits = [EventKey.MAX_LENGTH]. */
        private const val LONG_EVENT_BODY = 128 - 7 - 2
    }
}
