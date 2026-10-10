package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessMachineSideWritesTest {
    @Test
    fun `attachment writes are serialized correlated and leave harness revision unchanged`() {
        val write = HarnessAttachmentWrite(request, harness.id, session, true, mapOf(session to setOf(harness.id)))
        val pending = ready.copy(attachmentWrite = write, writeGeneration = 1)
        HarnessMachineSpec.assertTransition(
            ready,
            HarnessIntent.Public.Attach(request, harness.id, session),
            pending,
            effects = listOf(HarnessEffect.SaveAttachments(write)),
        )
        pending.refuses(HarnessIntent.Public.Detach(request, harness.id, session), HarnessRejection.Busy)
        pending.refuses(HarnessIntent.Public.Delete(request, harness.id, 0), HarnessRejection.Busy)
        HarnessMachineSpec.assertIgnored(
            pending,
            HarnessIntent.Internal.AttachmentsSaved(write.copy(requestId = RequestId("stale"))),
        )
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.AttachmentsFailed(write),
            ready.copy(writeGeneration = 1),
            outputs = listOf(HarnessOutput.StorageFailed(request)),
        )
        val attached = ready.copy(attachments = write.attachments, revision = 1, writeGeneration = 1)
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.AttachmentsSaved(write),
            attached,
            outputs = listOf(HarnessOutput.Attached(request, harness.id, session)),
        )
        val detach = write.copy(isAttached = false, attachments = emptyMap(), generation = 2)
        val removing = attached.copy(attachmentWrite = detach, writeGeneration = 2)
        HarnessMachineSpec.assertTransition(
            attached,
            HarnessIntent.Public.Detach(request, harness.id, session),
            removing,
            effects = listOf(HarnessEffect.SaveAttachments(detach)),
        )
        HarnessMachineSpec.assertTransition(
            removing,
            HarnessIntent.Internal.AttachmentsSaved(detach),
            ready.copy(revision = 2, writeGeneration = 2),
            outputs = listOf(HarnessOutput.Detached(request, harness.id, session)),
        )
        assertEquals(0, attached.harnesses.single().harness.revision)
    }

    @Test
    fun `attachments respect per harness limit but an existing attachment is idempotent`() {
        val attachments = (1..HarnessLimits.ATTACHED_PER_HARNESS).associate {
            session.copy(
                nativeId = "s$it",
            ) to setOf(harness.id)
        }
        val full = ready.copy(attachments = attachments)
        full.refuses(HarnessIntent.Public.Attach(request, harness.id, session), HarnessRejection.Limit)
        val existing = attachments.keys.first()
        val write = HarnessAttachmentWrite(request, harness.id, existing, true, attachments)
        HarnessMachineSpec.assertTransition(
            full,
            HarnessIntent.Public.Attach(request, harness.id, existing),
            full.copy(attachmentWrite = write, writeGeneration = 1),
            effects = listOf(HarnessEffect.SaveAttachments(write)),
        )
        ready.refuses(HarnessIntent.Public.Attach(request, HarnessId("missing"), session), HarnessRejection.NotFound)
    }

    @Test
    fun `approval changes only on matching durable receipt and stale public revision conflicts`() {
        val intent = HarnessIntent.Public.SetApproval(request, HarnessApproval.AcceptAll, 0)
        val write = HarnessApprovalWrite(request, HarnessApproval.AcceptAll, 1)
        val pending = ready.copy(approvalWrite = write, writeGeneration = 1)
        HarnessMachineSpec.assertTransition(ready, intent, pending, effects = listOf(HarnessEffect.SaveApproval(write)))
        pending.refuses(intent, HarnessRejection.Busy)
        pending.refuses(intent.copy(expectedRevision = 7), HarnessRejection.Conflict)
        HarnessMachineSpec.assertIgnored(pending, HarnessIntent.Internal.ApprovalSaved(write.copy(revision = 2)))
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.ApprovalFailed(write),
            ready.copy(writeGeneration = 1),
            outputs = listOf(HarnessOutput.StorageFailed(request)),
        )
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.ApprovalSaved(write),
            ready.copy(approval = HarnessApproval.AcceptAll, approvalRevision = 1, revision = 1, writeGeneration = 1),
            outputs = listOf(HarnessOutput.ApprovalChanged(request, HarnessApproval.AcceptAll)),
        )
    }

    @Test
    fun `late side write replies cannot settle a retry with identical public fields`() {
        val attach = HarnessIntent.Public.Attach(request, harness.id, session)
        val first = ready.send(attach)
        val original = checkNotNull(first.attachmentWrite)
        val retried = first.send(HarnessIntent.Internal.AttachmentsFailed(original)).send(attach)
        HarnessMachineSpec.assertIgnored(retried, HarnessIntent.Internal.AttachmentsSaved(original))
        HarnessMachineSpec.assertIgnored(retried, HarnessIntent.Internal.AttachmentsFailed(original))
        assertEquals(2, retried.attachmentWrite?.generation)
        val approve = HarnessIntent.Public.SetApproval(request, HarnessApproval.AcceptAll, 0)
        val approval = ready.send(approve)
        val previous = checkNotNull(approval.approvalWrite)
        val retry = approval.send(HarnessIntent.Internal.ApprovalFailed(previous)).send(approve)
        HarnessMachineSpec.assertIgnored(retry, HarnessIntent.Internal.ApprovalSaved(previous))
        HarnessMachineSpec.assertIgnored(retry, HarnessIntent.Internal.ApprovalFailed(previous))
        assertEquals(2, retry.approvalWrite?.generation)
    }
}
