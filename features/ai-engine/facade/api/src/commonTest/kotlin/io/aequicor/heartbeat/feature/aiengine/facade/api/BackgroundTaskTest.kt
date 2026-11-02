package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackgroundTaskTest {
    private val running = BackgroundTaskSnapshot(
        BackgroundTaskRef("task", RequestId("attempt")),
        BackgroundTaskPhase.Running,
        BackgroundTaskLifetime.Session,
    )

    @Test
    fun `launch receipt does not complete the attached background execution`() {
        val call = SessionItem.ToolCall(
            ItemInfo(ItemId("call"), 0, 0),
            ToolCallId("call"),
            "run_command",
            "{}",
            ToolCallStatus.Succeeded,
            background = running,
        )
        assertFalse(call.background?.phase?.isTerminal == true)
        assertEquals(running, Json.decodeFromString<SessionItem.ToolCall>(Json.encodeToString(call)).background)
    }

    @Test
    fun `legacy invocation reads without manufacturing a background execution`() {
        val call = SessionItem.ToolCall(
            ItemInfo(ItemId("call"), 0, 0),
            ToolCallId("call"),
            "read_file",
            "{}",
            ToolCallStatus.Succeeded,
        )
        assertNull(Json.decodeFromString<SessionItem.ToolCall>(Json.encodeToString(call)).background)
    }

    @Test
    fun `replayed output and another attempt cannot replace the current observation`() {
        val current = running.copy(revision = 2, output = BackgroundTaskOutput("first"))
        assertSame(current, current.observed(current.copy(output = BackgroundTaskOutput("replayed"))))
        assertSame(current, current.observed(running))
        assertSame(current, current.observed(current.copy(ref = running.ref.copy(attempt = RequestId("another")))))
        val newer = current.copy(revision = 3, output = BackgroundTaskOutput("first\nsecond"))
        assertEquals(newer, current.observed(newer))
    }

    @Test
    fun `late progress cannot revive a cancelled execution`() {
        val cancelled = running.copy(phase = BackgroundTaskPhase.Cancelled, revision = 3)
        assertSame(cancelled, cancelled.observed(running.copy(revision = 4)))
        assertSame(cancelled, cancelled.observed(running.copy(phase = BackgroundTaskPhase.Succeeded, revision = 4)))
        val enriched = cancelled.copy(revision = 4, output = BackgroundTaskOutput("final available output"))
        assertEquals(enriched, cancelled.observed(enriched))
    }

    @Test
    fun `live progress preserves the stop intent while updating output`() {
        val stopping = running.copy(phase = BackgroundTaskPhase.StopUnconfirmed, revision = 1)
        val progress = running.copy(revision = 2, output = BackgroundTaskOutput("last output"))
        val observed = stopping.observed(progress)
        assertEquals(BackgroundTaskPhase.StopUnconfirmed, observed.phase)
        assertEquals(progress.output, observed.output)
        assertEquals(2L, observed.revision)
        val terminal = progress.copy(phase = BackgroundTaskPhase.Cancelled, revision = 3)
        assertEquals(terminal, observed.observed(terminal))
    }

    @Test
    fun `unconfirmed stop and missing observations are not terminal`() {
        val uncertain = listOf(
            BackgroundTaskPhase.Stopping,
            BackgroundTaskPhase.StopUnconfirmed,
            BackgroundTaskPhase.Unknown,
        )
        for (phase in uncertain) {
            assertFalse(phase.isTerminal)
        }
        val terminal = listOf(
            BackgroundTaskPhase.Succeeded,
            BackgroundTaskPhase.Failed,
            BackgroundTaskPhase.Cancelled,
        )
        for (phase in terminal) {
            assertTrue(phase.isTerminal)
        }
    }

    @Test
    fun `partial output remains distinguishable from a complete empty stream`() {
        val partial = BackgroundTaskOutput("", 100, BackgroundOutputCoverage.Partial, BackgroundOutputMode.Stream)
        val complete = BackgroundTaskOutput("", coverage = BackgroundOutputCoverage.Complete)
        assertEquals(BackgroundOutputCoverage.Partial, partial.coverage)
        assertEquals(BackgroundOutputCoverage.Complete, complete.coverage)
        assertEquals(partial, Json.decodeFromString<BackgroundTaskOutput>(Json.encodeToString(partial)))
    }
}
