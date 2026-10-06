package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Instant

class StudioHelperAdmissionTest {
    private val record = StudioChatRecord(
        "helper",
        "Helper",
        Instant.fromEpochMilliseconds(0),
        helper = StudioHelperIdentity(ActionId("wf_owner"), null, null, TrustLevel.Ask),
    )

    @Test
    fun `manual helper send journals before native begin and refuses an unresolved old request`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val admission = StudioHelperAdmission(attempts)
        val request = runRequest("manual").copy(id = record.id)
        val admitted = admission.prepare(record, request)
        assertEquals(StudioHelperPhase.Preparing, attempts.receipt(HelperId(record.id), request.request)?.phase)
        assertNotNull(admitted.submission).begin()
        assertEquals(StudioHelperPhase.Submitting, attempts.receipt(HelperId(record.id), request.request)?.phase)
        assertFailsWith<IllegalStateException> {
            admission.prepare(record, runRequest("another").copy(id = record.id))
        }
        assertFailsWith<IllegalStateException> { admission.prepare(record, request) }
    }

    @Test
    fun `a helper gate belonging to another chat or request is rejected`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val request = runRequest("manual").copy(id = record.id)
        val helper = HelperId(record.id)
        attempts.prepare(helper, HelperPrompt(request.request, request.prompt))
        val gate = StudioHelperSubmission(attempts, helper, request.request)
        val matching = request.copy(submission = gate)
        assertSame(matching, StudioHelperAdmission(attempts).prepare(record, matching))
        assertFailsWith<IllegalStateException> {
            StudioHelperAdmission(attempts).prepare(record.copy(id = "foreign"), matching)
        }
    }

    @Test
    fun `ordinary chats remain ordinary and prior scheduled admission still prevents helper native send`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val admission = StudioHelperAdmission(attempts)
        val prior = StudioRunSubmission()
        val request = runRequest("wake").copy(id = record.id, submission = prior)
        assertSame(request, admission.prepare(record.copy(helper = null), request))
        val admitted = admission.prepare(record, request)
        prior.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { checkNotNull(admitted.submission).begin() }
        admitted.submission?.cancel()
        assertEquals(StudioHelperPhase.NotSubmitted, attempts.receipt(HelperId(record.id), request.request)?.phase)
    }
}
