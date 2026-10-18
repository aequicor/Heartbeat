package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProcessDefinitionTest {
    private val original = ProcessDefinition(ItemId("script"), 1)
    private val replacement = original.copy(revision = 2)

    @Test
    fun `explicit migration preserves the wait and all evidence`() {
        val condition = WakeCondition(events = setOf(EventKeys.custom("process.answer")))
        val record = newProcess().copy(definition = original)
            .attempt(planStage, succeeded("plan-1"))
            .change(ProcessCommand.Wait(WaitReason.External, condition))
        val migrated = record.change(ProcessCommand.MigrateDefinition(original, replacement))
        assertEquals(replacement, migrated.definition)
        assertEquals(record.history, migrated.history)
        assertEquals(record.phase, migrated.phase)
        assertEquals(record.incarnation, migrated.incarnation)
        migrated.expectUnchanged(ProcessCommand.MigrateDefinition(replacement, replacement))
        migrated.expectRejected(
            ProcessCommand.MigrateDefinition(original, replacement),
            ProcessRejection.DefinitionMismatch,
        )
    }

    @Test
    fun `migration refuses live executors and uncertain external writes`() {
        val record = newProcess().copy(definition = original)
        val command = ProcessCommand.MigrateDefinition(original, replacement)
        record.change(ProcessCommand.Start(planStage, helperExecutor))
            .expectRejected(command, ProcessRejection.AttemptInFlight)
        record.change(ProcessCommand.Grant(ProcessAuthority.Write))
            .change(ProcessCommand.BeginWrite(WriteKey("publish"), ProcessAuthority.Write, evidence("target")))
            .expectRejected(command, ProcessRejection.WriteUnresolved)
        record.change(ProcessCommand.Close(ProcessEnd.Completed)).expectRejected(command, ProcessRejection.Closed)
    }

    @Test
    fun `legacy records remain readable but newer schemas fail closed`() {
        val legacy = Json.decodeFromString<ProcessRecord>(
            """{"id":"p1","createdAt":"1970-01-01T00:16:40Z"}""",
        )
        assertEquals(null, legacy.definition)
        assertEquals(1, legacy.schemaVersion)
        val migrated = legacy.change(ProcessCommand.MigrateDefinition(null, replacement))
        assertEquals(replacement, migrated.definition)
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<ProcessRecord>(
                """{"id":"p1","createdAt":"1970-01-01T00:16:40Z","schemaVersion":2}""",
            )
        }
    }
}
