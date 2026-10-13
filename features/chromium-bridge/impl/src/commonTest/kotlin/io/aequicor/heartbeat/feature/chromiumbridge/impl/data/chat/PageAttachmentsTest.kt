package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineKey
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsState
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.WirePage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PageAttachmentsTest {
    private val page = WirePage(
        url = "https://example.com/docs/a",
        title = "Docs: A/B",
        text = "Body",
        selection = "Pick",
    )

    @Test
    fun `pages become markdown attachments in the profile`() = runTest {
        val machine = AttachmentsMachine { request ->
            AttachmentsOutput.Imported(
                request.requestId,
                listOf(AttachmentDescriptor(AttachmentId("a1"), "Docs A B.md", "text/markdown", 10)),
            )
        }

        val result = PageAttachments(Registry(machine)).import(listOf(page))

        assertEquals("attachment:a1", (result as PageImport.Imported).resources.single().id)
        val input = (machine.sent.single() as AttachmentsIntent.Public.Import).inputs.single() as AttachmentInput.Bytes
        assertEquals("Docs  A B.md", input.name)
        assertEquals("text/markdown", input.mediaType)
        val document = input.bytes.decodeToString()
        assertTrue(document.startsWith("# Docs: A/B\n\nSource: https://example.com/docs/a\n"), document)
        assertTrue("## Selection\n\nPick" in document && "## Page text\n\nBody" in document, document)
    }

    @Test
    fun `failures and busy attachments refuse the import`() = runTest {
        val failing = AttachmentsMachine { AttachmentsOutput.Failed(it.requestId, AttachmentFailure.TooLarge) }
        val busy = AttachmentsMachine(result = SendResult.Ignored) { error("Unused") }

        assertIs<PageImport.Refused>(PageAttachments(Registry(failing)).import(listOf(page)))
        assertIs<PageImport.Refused>(PageAttachments(Registry(busy)).import(listOf(page)))
        assertEquals(PageImport.Imported(emptyList()), PageAttachments(Registry(busy)).import(emptyList()))
    }

    private class AttachmentsMachine(
        private val result: SendResult = SendResult.Accepted,
        private val answer: (AttachmentsIntent.Public.Import) -> AttachmentsOutput,
    ) : MachineRef<AttachmentsState, AttachmentsIntent.Public, AttachmentsOutput> {
        override val name = "attachments"
        override val state = MutableStateFlow<AttachmentsState>(AttachmentsState.Ready)
        override val outputs = MutableSharedFlow<AttachmentsOutput>(extraBufferCapacity = 4)
        val sent = mutableListOf<AttachmentsIntent.Public>()

        override suspend fun send(intent: AttachmentsIntent.Public): SendResult {
            sent += intent
            if (result == SendResult.Accepted && intent is AttachmentsIntent.Public.Import) outputs.emit(answer(intent))
            return result
        }
    }

    private class Registry(private val machine: AttachmentsMachine) : MachineRegistry {
        @Suppress("UNCHECKED_CAST") // This fixture registers only the attachments machine.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? {
            check(key == AttachmentsMachineKey)
            return machine as MachineRef<S, P, O>
        }

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = requireNotNull(find(key)).send(intent)
    }
}
