package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessWriteTransitionsTest {
    private val comment = WriteKey("comment_plan")
    private val pullRequest = ResultRef("pull_request", "pr-7")
    private val commentDone = ResultRef("comment", "c-1")
    private val answer = WakeCondition(events = setOf(EventKeys.custom("process.answer")))

    private fun granted(vararg authorities: ProcessAuthority): ProcessRecord =
        authorities.fold(newProcess()) { process, authority -> process.change(ProcessCommand.Grant(authority)) }

    private fun begin(key: WriteKey = comment, authority: ProcessAuthority = ProcessAuthority.Write) =
        ProcessCommand.BeginWrite(key, authority, pullRequest)

    @Test
    fun `a write needs a granted authority`() {
        newProcess().expectRejected(begin(), ProcessRejection.NotAuthorized)
    }

    @Test
    fun `every authority is granted on its own`() {
        val process = granted(ProcessAuthority.Write)
        process.expectRejected(begin(authority = ProcessAuthority.Publish), ProcessRejection.NotAuthorized)
        process.expectRejected(begin(authority = ProcessAuthority.Merge), ProcessRejection.NotAuthorized)
        process.expectRejected(begin(authority = ProcessAuthority.Release), ProcessRejection.NotAuthorized)
        process.change(begin())

        val merging = process.change(ProcessCommand.Grant(ProcessAuthority.Merge))
        merging.change(begin(WriteKey("merge_pr"), ProcessAuthority.Merge))
        merging.expectRejected(begin(authority = ProcessAuthority.Publish), ProcessRejection.NotAuthorized)
    }

    @Test
    fun `granting and revoking twice changes nothing the second time`() {
        val process = granted(ProcessAuthority.Publish)
        process.expectUnchanged(ProcessCommand.Grant(ProcessAuthority.Publish))
        process.expectUnchanged(ProcessCommand.Revoke(ProcessAuthority.Merge))

        val revoked = process.change(ProcessCommand.Revoke(ProcessAuthority.Publish))
        assertTrue(revoked.grants.isEmpty())
        revoked.expectUnchanged(ProcessCommand.Revoke(ProcessAuthority.Publish))
    }

    @Test
    fun `beginning a write journals the intent first`() {
        val process = granted(ProcessAuthority.Write).change(begin(), seconds = 9)

        val entry = assertIs<ExternalWrite>(process.write(comment))
        assertEquals(WritePhase.Intended, entry.phase)
        assertEquals(
            ExternalWrite(comment, ProcessAuthority.Write, pullRequest, WritePhase.Intended, 1, processAt(9)),
            entry,
        )
        assertEquals(listOf(entry), process.unresolvedWrites())
    }

    @Test
    fun `a write whose outcome is open cannot be begun again`() {
        val intended = granted(ProcessAuthority.Write).change(begin())
        intended.expectRejected(begin(), ProcessRejection.WriteUnresolved)

        val unknown = intended.change(ProcessCommand.MarkWriteUnknown(comment, 1))
        unknown.expectRejected(begin(), ProcessRejection.WriteUnresolved)
        assertEquals(1, unknown.write(comment)?.tries)
    }

    @Test
    fun `an unknown write found applied is confirmed and never repeated`() {
        val unknown = granted(ProcessAuthority.Write).change(begin())
            .change(ProcessCommand.MarkWriteUnknown(comment, 1))
        assertEquals(WritePhase.Unknown, unknown.write(comment)?.phase)

        val confirmed = unknown.change(ProcessCommand.ConfirmWrite(comment, commentDone))
        assertEquals(WritePhase.Confirmed(commentDone), confirmed.write(comment)?.phase)
        assertTrue(confirmed.unresolvedWrites().isEmpty())
        confirmed.expectUnchanged(begin())
        confirmed.expectUnchanged(ProcessCommand.ConfirmWrite(comment, commentDone))
        confirmed.expectRejected(
            ProcessCommand.ConfirmWrite(comment, ResultRef("comment", "c-2")),
            ProcessRejection.OutcomeConflict,
        )
    }

    @Test
    fun `a write known not to have happened may be tried again`() {
        val failed = granted(ProcessAuthority.Write).change(begin()).change(ProcessCommand.WriteNotApplied(comment, 1))
        assertEquals(WritePhase.NotApplied, failed.write(comment)?.phase)
        failed.expectUnchanged(ProcessCommand.WriteNotApplied(comment, 1))

        val again = failed.change(begin(), seconds = 20)
        assertEquals(WritePhase.Intended, again.write(comment)?.phase)
        assertEquals(2, again.write(comment)?.tries)
        assertEquals(processAt(20), again.write(comment)?.at)
    }

    @Test
    fun `an unknown write found absent is settled as not applied`() {
        val unknown = granted(ProcessAuthority.Write).change(begin())
            .change(ProcessCommand.MarkWriteUnknown(comment, 1))
        val absent = unknown.change(ProcessCommand.WriteNotApplied(comment, 1))
        assertEquals(WritePhase.NotApplied, absent.write(comment)?.phase)
        absent.expectRejected(ProcessCommand.ConfirmWrite(comment, commentDone), ProcessRejection.OutcomeConflict)
    }

    @Test
    fun `a confirmed write cannot become unknown or not applied`() {
        val confirmed = granted(ProcessAuthority.Write).change(begin())
            .change(ProcessCommand.ConfirmWrite(comment, commentDone))
        confirmed.expectRejected(ProcessCommand.MarkWriteUnknown(comment, 1), ProcessRejection.OutcomeConflict)
        confirmed.expectRejected(ProcessCommand.WriteNotApplied(comment, 1), ProcessRejection.OutcomeConflict)
    }

    @Test
    fun `marking an already unknown write unknown changes nothing`() {
        val unknown = granted(ProcessAuthority.Write).change(begin())
            .change(ProcessCommand.MarkWriteUnknown(comment, 1))
        unknown.expectUnchanged(ProcessCommand.MarkWriteUnknown(comment, 1))
    }

    @Test
    fun `settling a write that was never journaled is rejected`() {
        val process = granted(ProcessAuthority.Write)
        process.expectRejected(ProcessCommand.ConfirmWrite(comment, commentDone), ProcessRejection.UnknownWrite)
        process.expectRejected(ProcessCommand.MarkWriteUnknown(comment, 1), ProcessRejection.UnknownWrite)
        process.expectRejected(ProcessCommand.WriteNotApplied(comment, 1), ProcessRejection.UnknownWrite)
    }

    @Test
    fun `a key keeps its authority and target`() {
        val process = granted(ProcessAuthority.Write, ProcessAuthority.Publish).change(begin())
        process.expectRejected(begin(authority = ProcessAuthority.Publish), ProcessRejection.WriteKeyConflict)
        process.expectRejected(
            ProcessCommand.BeginWrite(comment, ProcessAuthority.Write, ResultRef("pull_request", "pr-8")),
            ProcessRejection.WriteKeyConflict,
        )
    }

    @Test
    fun `revoking an authority stops new writes but not the settlement of open ones`() {
        val open = granted(ProcessAuthority.Merge).change(begin(WriteKey("merge_pr"), ProcessAuthority.Merge))
        val revoked = open.change(ProcessCommand.Revoke(ProcessAuthority.Merge))

        revoked.expectRejected(begin(WriteKey("merge_other"), ProcessAuthority.Merge), ProcessRejection.NotAuthorized)
        val settled = revoked.change(ProcessCommand.ConfirmWrite(WriteKey("merge_pr"), commentDone))
        settled.expectUnchanged(begin(WriteKey("merge_pr"), ProcessAuthority.Merge))
    }

    @Test
    fun `a write that failed cannot be tried again without the authority`() {
        val failed = granted(ProcessAuthority.Publish)
            .change(begin(authority = ProcessAuthority.Publish))
            .change(ProcessCommand.WriteNotApplied(comment, 1))
            .change(ProcessCommand.Revoke(ProcessAuthority.Publish))
        failed.expectRejected(begin(authority = ProcessAuthority.Publish), ProcessRejection.NotAuthorized)
    }

    @Test
    fun `an ended process takes no new writes but still records late outcomes`() {
        val running = granted(ProcessAuthority.Write).change(begin())
        val ended = running.change(ProcessCommand.Close(ProcessEnd.Failed))

        ended.expectRejected(begin(WriteKey("other")), ProcessRejection.Closed)
        ended.expectRejected(ProcessCommand.Grant(ProcessAuthority.Merge), ProcessRejection.Closed)
        val late = ended.change(ProcessCommand.ConfirmWrite(comment, commentDone))
        assertEquals(WritePhase.Confirmed(commentDone), late.write(comment)?.phase)
        assertIs<ProcessPhase.Ended>(late.phase)
    }

    @Test
    fun `the journal forgets the oldest settled write to make room`() {
        var process = granted(ProcessAuthority.Write)
        repeat(ProcessLimits.WRITES) { n ->
            val key = WriteKey("w$n")
            process = process.change(begin(key)).change(ProcessCommand.ConfirmWrite(key, commentDone))
        }
        assertEquals(ProcessLimits.WRITES, process.writes.size)

        process = process.change(begin(WriteKey("extra")))
        assertEquals(ProcessLimits.WRITES, process.writes.size)
        assertNull(process.write(WriteKey("w0")))
        assertEquals(WritePhase.Intended, process.write(WriteKey("extra"))?.phase)
    }

    @Test
    fun `the journal never forgets an open write`() {
        var process = granted(ProcessAuthority.Write)
        repeat(ProcessLimits.WRITES) { n -> process = process.change(begin(WriteKey("w$n"))) }

        process.expectRejected(begin(WriteKey("extra")), ProcessRejection.TooManyWrites)
        assertEquals(ProcessLimits.WRITES, process.unresolvedWrites().size)

        val room = process.change(ProcessCommand.WriteNotApplied(WriteKey("w3"), 4))
        val next = room.change(begin(WriteKey("extra")))
        assertNull(next.write(WriteKey("w3")))
        assertEquals(ProcessLimits.WRITES, next.writes.size)
    }

    @Test
    fun `a decision about an older try never touches a newer one`() {
        val retried = granted(ProcessAuthority.Write)
            .change(begin())
            .change(ProcessCommand.MarkWriteUnknown(comment, 1))
            .change(ProcessCommand.WriteNotApplied(comment, 1))
            .change(begin())
        assertEquals(2, retried.write(comment)?.tries)
        assertEquals(WritePhase.Intended, retried.write(comment)?.phase)

        retried.expectUnchanged(ProcessCommand.WriteNotApplied(comment, 1))
        retried.expectUnchanged(ProcessCommand.MarkWriteUnknown(comment, 1))
        retried.expectRejected(begin(), ProcessRejection.WriteUnresolved)

        val unknown = retried.change(ProcessCommand.MarkWriteUnknown(comment, 2))
        unknown.expectUnchanged(ProcessCommand.WriteNotApplied(comment, 1))
        val absent = unknown.change(ProcessCommand.WriteNotApplied(comment, 2))
        assertEquals(WritePhase.NotApplied, absent.write(comment)?.phase)
    }

    @Test
    fun `a try that was never issued is rejected`() {
        val begun = granted(ProcessAuthority.Write).change(begin())
        begun.expectRejected(ProcessCommand.MarkWriteUnknown(comment, 2), ProcessRejection.UnknownTry)
        begun.expectRejected(ProcessCommand.WriteNotApplied(comment, 2), ProcessRejection.UnknownTry)
    }

    @Test
    fun `an open write reports it is unresolved even when its authority was revoked`() {
        val revoked = granted(ProcessAuthority.Merge)
            .change(begin(WriteKey("merge_pr"), ProcessAuthority.Merge))
            .change(ProcessCommand.Revoke(ProcessAuthority.Merge))
        revoked.expectRejected(begin(WriteKey("merge_pr"), ProcessAuthority.Merge), ProcessRejection.WriteUnresolved)
    }

    @Test
    fun `writes are journaled and granted in every live phase`() {
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        val waiting = newProcess().change(ProcessCommand.Wait(WaitReason.Human, answer))
        for (process in listOf(running, waiting)) {
            val granted = process.change(ProcessCommand.Grant(ProcessAuthority.Write))
            val begun = granted.change(begin())
            assertEquals(WritePhase.Intended, begun.write(comment)?.phase)
            assertTrue(begun.change(ProcessCommand.Revoke(ProcessAuthority.Write)).grants.isEmpty())
        }
    }

    @Test
    fun `an ended process also records late unknown and not applied outcomes`() {
        val ended = granted(ProcessAuthority.Write).change(begin()).change(ProcessCommand.Close(ProcessEnd.Failed))
        val unknown = ended.change(ProcessCommand.MarkWriteUnknown(comment, 1))
        assertEquals(WritePhase.Unknown, unknown.write(comment)?.phase)
        val absent = unknown.change(ProcessCommand.WriteNotApplied(comment, 1))
        assertEquals(WritePhase.NotApplied, absent.write(comment)?.phase)
        ended.expectRejected(ProcessCommand.Revoke(ProcessAuthority.Write), ProcessRejection.Closed)
    }

    @Test
    fun `a write known not to have happened is forgotten before a confirmed one`() {
        var process = granted(ProcessAuthority.Write)
        repeat(ProcessLimits.WRITES) { n ->
            val key = WriteKey("w$n")
            process = process.change(begin(key))
            process = if (n == 1) {
                process.change(ProcessCommand.WriteNotApplied(key, n + 1))
            } else {
                process.change(ProcessCommand.ConfirmWrite(key, commentDone))
            }
        }

        val next = process.change(begin(WriteKey("extra")))
        assertNull(next.write(WriteKey("w1")))
        assertEquals(WritePhase.Confirmed(commentDone), next.write(WriteKey("w0"))?.phase)
        assertEquals(ProcessLimits.WRITES, next.writes.size)
    }

    @Test
    fun `a forgotten confirmed key begins a new write`() {
        var process = granted(ProcessAuthority.Write)
        repeat(ProcessLimits.WRITES + 1) { n ->
            val key = WriteKey("w$n")
            process = process.change(begin(key)).change(ProcessCommand.ConfirmWrite(key, commentDone))
        }
        assertNull(process.write(WriteKey("w0")))

        val again = process.change(begin(WriteKey("w0")))
        assertEquals(WritePhase.Intended, again.write(WriteKey("w0"))?.phase)
        assertEquals(ProcessLimits.WRITES + 2, again.write(WriteKey("w0"))?.tries)
    }

    @Test
    fun `a try number below one was never issued`() {
        val begun = granted(ProcessAuthority.Write).change(begin())
        for (tries in listOf(0, -1)) {
            begun.expectRejected(ProcessCommand.MarkWriteUnknown(comment, tries), ProcessRejection.UnknownTry)
            begun.expectRejected(ProcessCommand.WriteNotApplied(comment, tries), ProcessRejection.UnknownTry)
        }
    }

    @Test
    fun `a late decision about an older try is ignored once the newer try is settled`() {
        val retried = granted(ProcessAuthority.Write)
            .change(begin())
            .change(ProcessCommand.WriteNotApplied(comment, 1))
            .change(begin())

        val confirmed = retried.change(ProcessCommand.ConfirmWrite(comment, commentDone))
        confirmed.expectUnchanged(ProcessCommand.WriteNotApplied(comment, 1))
        confirmed.expectUnchanged(ProcessCommand.MarkWriteUnknown(comment, 1))

        val absent = retried.change(ProcessCommand.WriteNotApplied(comment, 2))
        absent.expectUnchanged(ProcessCommand.WriteNotApplied(comment, 1))
        absent.expectUnchanged(ProcessCommand.MarkWriteUnknown(comment, 1))
    }

    @Test
    fun `the journal forgets by the time of the last change and not by its place`() {
        var process = granted(ProcessAuthority.Write)
        val again = WriteKey("again")
        process = process.change(begin(again), seconds = 1)
            .change(ProcessCommand.WriteNotApplied(again, 1), seconds = 2)
        (1 until ProcessLimits.WRITES - 1).forEach { n ->
            val key = WriteKey("w$n")
            process = process.change(begin(key), seconds = 2L + n)
                .change(ProcessCommand.ConfirmWrite(key, commentDone), seconds = 2L + n)
        }
        process = process.change(begin(again), seconds = 20).change(ProcessCommand.ConfirmWrite(again, commentDone), 21)
        val last = WriteKey("last")
        process = process.change(begin(last), seconds = 22).change(ProcessCommand.ConfirmWrite(last, commentDone), 22)
        assertEquals(ProcessLimits.WRITES, process.writes.size)

        val next = process.change(begin(WriteKey("extra")), seconds = 30)
        assertEquals(WritePhase.Confirmed(commentDone), next.write(again)?.phase)
        assertNull(next.write(WriteKey("w1")))
    }

    @Test
    fun `a decision about a forgotten try cannot touch the key begun anew`() {
        val key = WriteKey("again")
        var process = granted(ProcessAuthority.Write)
            .change(begin(key))
            .change(ProcessCommand.WriteNotApplied(key, 1))
        repeat(ProcessLimits.WRITES - 1) { n -> process = process.change(begin(WriteKey("w$n"))) }
        process = process.change(ProcessCommand.ConfirmWrite(WriteKey("w0"), commentDone))
        // The journal is full and `again` is its only write known not to have happened, so it goes first.
        process = process.change(begin(WriteKey("extra")))
        assertNull(process.write(key))

        process = process.change(ProcessCommand.ConfirmWrite(WriteKey("extra"), commentDone))
        val renewed = process.change(begin(key))
        assertTrue(checkNotNull(renewed.write(key)).tries > 1)
        renewed.expectUnchanged(ProcessCommand.WriteNotApplied(key, 1))
        renewed.expectUnchanged(ProcessCommand.MarkWriteUnknown(key, 1))
        assertEquals(WritePhase.Intended, renewed.write(key)?.phase)
    }

    @Test
    fun `among writes known not to have happened the one settled longest ago is forgotten`() {
        var process = granted(ProcessAuthority.Write)
        val first = WriteKey("first")
        val second = WriteKey("second")
        process = process.change(begin(first), seconds = 1).change(begin(second), seconds = 2)
        (2 until ProcessLimits.WRITES).forEach { n ->
            val key = WriteKey("w$n")
            process = process.change(begin(key), seconds = 3).change(ProcessCommand.ConfirmWrite(key, commentDone), 3)
        }
        // `second` fails first, `first` fails later: the position in the journal says the opposite.
        process = process.change(ProcessCommand.WriteNotApplied(second, 2), seconds = 10)
            .change(ProcessCommand.WriteNotApplied(first, 1), seconds = 20)

        val next = process.change(begin(WriteKey("extra")), seconds = 30)
        assertNull(next.write(second))
        assertEquals(WritePhase.NotApplied, next.write(first)?.phase)
    }
}
