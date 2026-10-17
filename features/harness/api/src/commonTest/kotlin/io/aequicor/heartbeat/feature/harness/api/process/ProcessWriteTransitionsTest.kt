package io.aequicor.heartbeat.feature.harness.api.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessWriteTransitionsTest {
    private val comment = WriteKey("comment_plan")
    private val pullRequest = ResultRef("pull_request", "pr-7")
    private val commentDone = ResultRef("comment", "c-1")

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

        val unknown = intended.change(ProcessCommand.MarkWriteUnknown(comment))
        unknown.expectRejected(begin(), ProcessRejection.WriteUnresolved)
        assertEquals(1, unknown.write(comment)?.tries)
    }

    @Test
    fun `an unknown write found applied is confirmed and never repeated`() {
        val unknown = granted(ProcessAuthority.Write).change(begin()).change(ProcessCommand.MarkWriteUnknown(comment))
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
        val failed = granted(ProcessAuthority.Write).change(begin()).change(ProcessCommand.WriteNotApplied(comment))
        assertEquals(WritePhase.NotApplied, failed.write(comment)?.phase)
        failed.expectUnchanged(ProcessCommand.WriteNotApplied(comment))

        val again = failed.change(begin(), seconds = 20)
        assertEquals(WritePhase.Intended, again.write(comment)?.phase)
        assertEquals(2, again.write(comment)?.tries)
        assertEquals(processAt(20), again.write(comment)?.at)
    }

    @Test
    fun `an unknown write found absent is settled as not applied`() {
        val unknown = granted(ProcessAuthority.Write).change(begin()).change(ProcessCommand.MarkWriteUnknown(comment))
        val absent = unknown.change(ProcessCommand.WriteNotApplied(comment))
        assertEquals(WritePhase.NotApplied, absent.write(comment)?.phase)
        absent.expectRejected(ProcessCommand.ConfirmWrite(comment, commentDone), ProcessRejection.OutcomeConflict)
    }

    @Test
    fun `a confirmed write cannot become unknown or not applied`() {
        val confirmed = granted(ProcessAuthority.Write).change(begin())
            .change(ProcessCommand.ConfirmWrite(comment, commentDone))
        confirmed.expectRejected(ProcessCommand.MarkWriteUnknown(comment), ProcessRejection.OutcomeConflict)
        confirmed.expectRejected(ProcessCommand.WriteNotApplied(comment), ProcessRejection.OutcomeConflict)
    }

    @Test
    fun `marking an already unknown write unknown changes nothing`() {
        val unknown = granted(ProcessAuthority.Write).change(begin()).change(ProcessCommand.MarkWriteUnknown(comment))
        unknown.expectUnchanged(ProcessCommand.MarkWriteUnknown(comment))
    }

    @Test
    fun `settling a write that was never journaled is rejected`() {
        val process = granted(ProcessAuthority.Write)
        process.expectRejected(ProcessCommand.ConfirmWrite(comment, commentDone), ProcessRejection.UnknownWrite)
        process.expectRejected(ProcessCommand.MarkWriteUnknown(comment), ProcessRejection.UnknownWrite)
        process.expectRejected(ProcessCommand.WriteNotApplied(comment), ProcessRejection.UnknownWrite)
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
            .change(ProcessCommand.WriteNotApplied(comment))
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

        val room = process.change(ProcessCommand.WriteNotApplied(WriteKey("w3")))
        val next = room.change(begin(WriteKey("extra")))
        assertNull(next.write(WriteKey("w3")))
        assertEquals(ProcessLimits.WRITES - 1 + 1, next.writes.size)
    }
}
