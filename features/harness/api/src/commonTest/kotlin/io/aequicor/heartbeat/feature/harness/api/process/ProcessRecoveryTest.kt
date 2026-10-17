package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessRecoveryTest {
    private val answer = WakeCondition(events = setOf(EventKeys.custom("process.answer")))
    private val target = ResultRef("pull_request", "pr-7")

    private fun granted(): ProcessRecord = newProcess().change(ProcessCommand.Grant(ProcessAuthority.Write))

    @Test
    fun `a fresh or idle process leaves nothing open`() {
        assertTrue(newProcess().recovery(processAt(100)).isEmpty())
        assertTrue(newProcess().attempt(planStage, succeeded("plan-1")).recovery(processAt(100)).isEmpty())
    }

    @Test
    fun `an attempt in flight is open because nobody knows what became of its executor`() {
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        val attempt = assertIs<ProcessPhase.Running>(running.phase).attempt
        assertEquals(listOf(ProcessRecovery.AttemptOpen(attempt)), running.recovery(processAt(100)))
    }

    @Test
    fun `a wait is always open and says whether its deadline has passed`() {
        val deadline = processAt(50)
        val timed = WakeCondition(events = setOf(EventKeys.custom("process.answer")), deadline = deadline)
        val waiting = newProcess().change(ProcessCommand.Wait(WaitReason.Human, timed), seconds = 10)
        val wait = assertIs<ProcessPhase.Waiting>(waiting.phase).wait

        assertEquals(listOf(ProcessRecovery.WaitOpen(wait, isDue = false)), waiting.recovery(processAt(49)))
        assertEquals(listOf(ProcessRecovery.WaitOpen(wait, isDue = true)), waiting.recovery(deadline))
        assertEquals(listOf(ProcessRecovery.WaitOpen(wait, isDue = true)), waiting.recovery(processAt(500)))

        val eventsOnly = newProcess().change(ProcessCommand.Wait(WaitReason.External, answer))
        val onlyEvents = assertIs<ProcessPhase.Waiting>(eventsOnly.phase).wait
        val later = eventsOnly.recovery(processAt(10_000))
        assertEquals(listOf(ProcessRecovery.WaitOpen(onlyEvents, isDue = false)), later)
    }

    @Test
    fun `open writes are listed oldest first and settled ones are not`() {
        val process = granted()
            .change(ProcessCommand.BeginWrite(WriteKey("first"), ProcessAuthority.Write, target))
            .change(ProcessCommand.BeginWrite(WriteKey("second"), ProcessAuthority.Write, target))
            .change(ProcessCommand.BeginWrite(WriteKey("third"), ProcessAuthority.Write, target))
            .change(ProcessCommand.ConfirmWrite(WriteKey("second"), ResultRef("comment", "c-1")))
            .change(ProcessCommand.MarkWriteUnknown(WriteKey("third")))

        val open = process.recovery(processAt(100)).map { assertIs<ProcessRecovery.WriteOpen>(it).write.key.value }
        assertEquals(listOf("first", "third"), open)
    }

    @Test
    fun `the attempt comes first then the writes then the wait`() {
        val running = granted()
            .change(ProcessCommand.Start(planStage, helperExecutor))
            .change(ProcessCommand.BeginWrite(WriteKey("comment_plan"), ProcessAuthority.Write, target))
        val kinds = running.recovery(processAt(100)).map { it::class }
        assertEquals(listOf(ProcessRecovery.AttemptOpen::class, ProcessRecovery.WriteOpen::class), kinds)

        val waiting = granted()
            .change(ProcessCommand.BeginWrite(WriteKey("comment_plan"), ProcessAuthority.Write, target))
            .change(ProcessCommand.Wait(WaitReason.Human, answer))
        assertEquals(
            listOf(ProcessRecovery.WriteOpen::class, ProcessRecovery.WaitOpen::class),
            waiting.recovery(processAt(100)).map { it::class },
        )
    }

    @Test
    fun `an ended process still lists its open writes until they are settled`() {
        val ended = granted()
            .change(ProcessCommand.BeginWrite(WriteKey("comment_plan"), ProcessAuthority.Write, target))
            .change(ProcessCommand.Close(ProcessEnd.Failed))
        assertEquals(1, ended.recovery(processAt(100)).size)

        val settled = ended.change(ProcessCommand.WriteNotApplied(WriteKey("comment_plan")))
        assertTrue(settled.recovery(processAt(100)).isEmpty())
    }

    @Test
    fun `settling what recovery listed empties it`() {
        val interrupted = granted()
            .change(ProcessCommand.Start(planStage, helperExecutor))
            .change(ProcessCommand.BeginWrite(WriteKey("comment_plan"), ProcessAuthority.Write, target))
        val settled = interrupted
            .change(ProcessCommand.Finish(1, failed(AttemptFailure.Interrupted)))
            .change(ProcessCommand.MarkWriteUnknown(WriteKey("comment_plan")))
            .change(ProcessCommand.ConfirmWrite(WriteKey("comment_plan"), ResultRef("comment", "c-1")))
        assertTrue(settled.recovery(processAt(100)).isEmpty())
    }
}
