package io.aequicor.heartbeat.feature.attachments.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AttachmentsMachineTest {
    private val support = PromptInputSupport.TextDocuments
    private val input = AttachmentInput.Bytes("source.txt", "text/plain", byteArrayOf(65))
    private val item = AttachmentDescriptor(AttachmentId("a"), "source.txt", "text/plain", 1)

    @Test
    fun `transient source details and names are redacted while history identity stays stable`() {
        val file = AttachmentInput.File("/private/personal/document.txt")
        val descriptor = AttachmentDescriptor(AttachmentId("id"), "personal document.txt", "text/plain", 1)
        assertFalse(file.toString().contains("personal"))
        assertFalse(descriptor.toString().contains("personal"))
        assertFalse(AttachmentSelection("r", listOf(descriptor)).toString().contains("personal"))
        assertEquals("attachment:id", descriptor.resource.id)
    }

    @Test
    fun `profile startup prepares storage then becomes ready`() {
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Idle,
            AttachmentsIntent.Public.Start,
            AttachmentsState.Preparing,
            effects = listOf(AttachmentsEffect.Prepare),
        )
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Preparing,
            AttachmentsIntent.Internal.Prepared,
            AttachmentsState.Ready,
        )
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Preparing,
            AttachmentsIntent.Internal.PrepareFailed,
            AttachmentsState.Error(AttachmentFailure.Unavailable),
        )
    }

    @Test
    fun `import publishes only durable effect results`() {
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Ready,
            AttachmentsIntent.Public.Import("r", listOf(input), support),
            AttachmentsState.Performing("r", AttachmentOperationPhase.Importing),
            effects = listOf(AttachmentsEffect.Import("r", listOf(input), support)),
        )
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Performing("r", AttachmentOperationPhase.Importing),
            AttachmentsIntent.Internal.Imported("r", listOf(item)),
            AttachmentsState.Ready,
            outputs = listOf(AttachmentsOutput.Imported("r", listOf(item))),
        )
    }

    @Test
    fun `migration forwards stable deduplication identity`() {
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Ready,
            AttachmentsIntent.Public.Import(
                "r",
                listOf(input),
                support,
                AttachmentImportPurpose.Migration,
                "research:1",
            ),
            AttachmentsState.Performing("r", AttachmentOperationPhase.Importing),
            effects = listOf(
                AttachmentsEffect.Import("r", listOf(input), support, AttachmentImportPurpose.Migration, "research:1"),
            ),
        )
    }

    @Test
    fun `native choose waits for lifecycle feedback then imports`() {
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Ready,
            AttachmentsIntent.Public.Choose("r", support),
            AttachmentsState.Performing("r"),
            outputs = listOf(AttachmentsOutput.NativeRequested("r", AttachmentNativeOperation.Choose(support))),
        )
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Performing("r"),
            AttachmentsIntent.Internal.Selected("r", listOf(input), support),
            AttachmentsState.Performing("r", AttachmentOperationPhase.Importing),
            effects = listOf(AttachmentsEffect.Import("r", listOf(input), support)),
        )
    }

    @Test
    fun `open and export remain correlated`() {
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Ready,
            AttachmentsIntent.Public.Open("r", item.id),
            AttachmentsState.Performing("r"),
            outputs = listOf(AttachmentsOutput.NativeRequested("r", AttachmentNativeOperation.Open(item.id))),
        )
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Ready,
            AttachmentsIntent.Public.Export("r", item.id),
            AttachmentsState.Performing("r"),
            outputs = listOf(AttachmentsOutput.NativeRequested("r", AttachmentNativeOperation.Export(item.id))),
        )
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Performing("r"),
            AttachmentsIntent.Internal.NativeCompleted("r"),
            AttachmentsState.Ready,
            outputs = listOf(AttachmentsOutput.Completed("r")),
        )
    }

    @Test
    fun `cancel returns ready and late feedback is ignored`() {
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Performing("r"),
            AttachmentsIntent.Public.Cancel("r"),
            AttachmentsState.Ready,
            outputs = listOf(AttachmentsOutput.Cancelled("r")),
        )
        AttachmentsMachineSpec.assertIgnored(
            AttachmentsState.Ready,
            AttachmentsIntent.Internal.Imported("r", listOf(item)),
        )
        AttachmentsMachineSpec.assertIgnored(
            AttachmentsState.Performing("next"),
            AttachmentsIntent.Internal.Imported("r", listOf(item)),
        )
        AttachmentsMachineSpec.assertIgnored(AttachmentsState.Performing("next"), AttachmentsIntent.Public.Cancel("r"))
    }

    @Test
    fun `duplicate native feedback cannot import twice`() {
        AttachmentsMachineSpec.assertIgnored(
            AttachmentsState.Performing("r", AttachmentOperationPhase.Importing),
            AttachmentsIntent.Internal.Selected("r", listOf(input), support),
        )
    }

    @Test
    fun `busy rejects new native requests and import`() {
        val busy = AttachmentsState.Performing("active")
        AttachmentsMachineSpec.assertIgnored(busy, AttachmentsIntent.Public.Choose("r", support))
        AttachmentsMachineSpec.assertIgnored(busy, AttachmentsIntent.Public.Import("r", listOf(input), support))
        AttachmentsMachineSpec.assertIgnored(busy, AttachmentsIntent.Public.Export("r", item.id))
        AttachmentsMachineSpec.assertIgnored(AttachmentsState.Preparing, AttachmentsIntent.Public.Choose("r", support))
    }

    @Test
    fun `failure is safe and a new request recovers`() {
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Performing("r"),
            AttachmentsIntent.Internal.Failed("r", AttachmentFailure.TooLarge),
            AttachmentsState.Error(AttachmentFailure.TooLarge),
            outputs = listOf(AttachmentsOutput.Failed("r", AttachmentFailure.TooLarge)),
        )
        AttachmentsMachineSpec.assertTransition(
            AttachmentsState.Error(AttachmentFailure.TooLarge),
            AttachmentsIntent.Public.Choose("next", support),
            AttachmentsState.Performing("next"),
            outputs = listOf(AttachmentsOutput.NativeRequested("next", AttachmentNativeOperation.Choose(support))),
        )
    }
}
