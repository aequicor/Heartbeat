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
    fun `a clock behind the record is clamped like in reduce`() {
        val deadline = processAt(100)
        val timed = WakeCondition(events = setOf(EventKeys.custom("process.answer")), deadline = deadline)
        val waiting = newProcess().change(ProcessCommand.Wait(WaitReason.Human, timed), seconds = 120)
        val wait = assertIs<ProcessPhase.Waiting>(waiting.phase).wait

        assertEquals(listOf(ProcessRecovery.WaitOpen(wait, isDue = true)), waiting.recovery(processAt(90)))
    }

    @Test
    fun `open writes are listed in journal order and settled ones are not`() {
        val process = granted()
            .change(ProcessCommand.BeginWrite(WriteKey("zeta"), ProcessAuthority.Write, target), seconds = 5)
            .change(ProcessCommand.BeginWrite(WriteKey("alpha"), ProcessAuthority.Write, target), seconds = 6)
            .change(ProcessCommand.BeginWrite(WriteKey("mid"), ProcessAuthority.Write, target), seconds = 7)
            .change(ProcessCommand.ConfirmWrite(WriteKey("alpha"), ResultRef("comment", "c-1")), seconds = 8)
            .change(ProcessCommand.MarkWriteUnknown(WriteKey("mid"), 3), seconds = 9)

        val open = process.recovery(processAt(100)).map { assertIs<ProcessRecovery.WriteOpen>(it).write.key.value }
        assertEquals(listOf("zeta", "mid"), open)
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
    fun `an ended process still lists its open writes and nothing else until they are settled`() {
        val comment = WriteKey("comment_plan")
        val begun = granted().change(ProcessCommand.BeginWrite(comment, ProcessAuthority.Write, target))
        val close = ProcessCommand.Close(ProcessEnd.Failed)
        val endings = listOf(
            begun.change(close),
            begun.change(ProcessCommand.Start(planStage, helperExecutor)).change(close),
            begun.change(ProcessCommand.Wait(WaitReason.Human, answer)).change(close),
        )
        for (ended in endings) {
            assertIs<ProcessPhase.Ended>(ended.phase)
            val open = ended.recovery(processAt(100)).single()
            assertEquals(comment, assertIs<ProcessRecovery.WriteOpen>(open).write.key)

            val settled = ended.change(ProcessCommand.WriteNotApplied(comment, 1))
            assertTrue(settled.recovery(processAt(100)).isEmpty())
        }
    }

    @Test
    fun `settling what recovery listed empties it`() {
        val interrupted = granted()
            .change(ProcessCommand.Start(planStage, helperExecutor))
            .change(ProcessCommand.BeginWrite(WriteKey("comment_plan"), ProcessAuthority.Write, target))
        var process = interrupted
        for (item in interrupted.recovery(processAt(100))) {
            process = when (item) {
                is ProcessRecovery.AttemptOpen -> process.change(
                    ProcessCommand.Finish(item.attempt.number, failed(AttemptFailure.Interrupted)),
                )

                is ProcessRecovery.WriteOpen ->
                    process
                        .change(ProcessCommand.MarkWriteUnknown(item.write.key, item.write.tries))
                        .change(ProcessCommand.ConfirmWrite(item.write.key, ResultRef("comment", "c-1")))

                is ProcessRecovery.WaitOpen -> error("no wait was open")
            }
        }
        assertTrue(process.recovery(processAt(100)).isEmpty())
    }

    @Test
    fun `an attempt that is kept alive and a wait that is kept stay listed`() {
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        assertEquals(1, running.recovery(processAt(100)).size)
        assertEquals(1, running.recovery(processAt(200)).size)

        val waiting = newProcess().change(ProcessCommand.Wait(WaitReason.Human, answer))
        assertEquals(1, waiting.recovery(processAt(100)).size)
        assertEquals(1, waiting.recovery(processAt(200)).size)
    }
}
