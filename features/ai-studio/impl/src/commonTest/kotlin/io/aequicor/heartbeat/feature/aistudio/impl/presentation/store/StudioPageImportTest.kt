package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.RemoteStudioView
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StudioPageImportTest {
    @Test
    fun `page uses model support and prevents submission until the correlated import returns`() = runTest {
        val attachments = FakeAttachments()
        val fixture = fixture(attachments)
        val screen = fixture.subscribe()
        val page = NativeAttachmentUi.Page("https://example.com", "Page", "Body", "Selected")
        screen.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "Read this"))
        val view = RemoteStudioView("view", {}, fixture.model, StandardTestDispatcher(testScheduler), 100.milliseconds)
        val encoded = StudioViewJson.encodeToJsonElement(
            AiStudioScreenIntent.serializer(),
            AiStudioScreenIntent.ImportAttachments(0, listOf(page)),
        )
        val input = listOf(StudioRemoteIntent(1, encoded))
        view.apply(view.generation, input)
        view.apply(view.generation, input)
        runCurrent()
        val imported = attachments.sent.single()
        assertEquals(setOf("text/markdown"), imported.support.resourceMediaTypes)
        val bytes = imported.inputs.single() as AttachmentInput.Bytes
        assertEquals("text/markdown", bytes.mediaType)
        assertEquals(page.document(), bytes.bytes.decodeToString())
        assertTrue(screen.states.value.isAttachmentPending(0))
        screen.intent(AiStudioScreenIntent.Submit(0))
        screen.intent(AiStudioScreenIntent.ImportAttachments(0, listOf(page)))
        runCurrent()
        assertEquals(1, attachments.sent.size)
        assertTrue(fixture.machine.sent.none { it is AiStudioIntent.Public.Submit })
        attachments.outputs.emit(AttachmentsOutput.Imported("stale", emptyList()))
        runCurrent()
        assertTrue(screen.states.value.isAttachmentPending(0))
        val file = AttachmentDescriptor(AttachmentId("a"), "Page.md", "text/markdown", bytes.bytes.size.toLong())
        attachments.outputs.emit(AttachmentsOutput.Imported(imported.requestId, listOf(file)))
        runCurrent()
        assertFalse(screen.states.value.isAttachmentPending(0))
        assertEquals("a", screen.states.value.attachments(0).single().id)
        assertEquals(StudioRemoteReply.UnknownView, view.apply("stale", input))
        view.apply(view.generation, input)
        runCurrent()
        assertEquals(1, attachments.sent.size)
        screen.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()
        assertEquals(1, fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().size)
    }

    @Test
    fun `completion while disconnected remains visible and a rejected import can be retried`() = runTest {
        val attachments = FakeAttachments()
        val fixture = fixture(attachments)
        val subscription = backgroundScope.launch { fixture.model.store.collect { awaitCancellation() } }
        runCurrent()
        val page = AiStudioScreenIntent.ImportAttachments(0, listOf(NativeAttachmentUi.Page("https://example.com")))
        fixture.model.store.intent(page)
        runCurrent()
        val request = attachments.sent.single().requestId
        subscription.cancelAndJoin()
        advanceTimeBy(2.seconds)
        runCurrent()
        attachments.outputs.emit(AttachmentsOutput.Failed(request, AttachmentFailure.Unsupported))
        runCurrent()
        val screen = fixture.subscribe()
        assertFalse(screen.states.value.isAttachmentPending(0))
        assertTrue(0 in screen.states.value.attachmentErrorPanes)
        attachments.result = SendResult.Ignored
        screen.intent(page)
        runCurrent()
        assertFalse(screen.states.value.isAttachmentPending(0))
        assertTrue(0 in screen.states.value.attachmentErrorPanes)
        attachments.result = SendResult.Accepted
        screen.intent(page)
        runCurrent()
        assertTrue(screen.states.value.isAttachmentPending(0))
        assertFalse(0 in screen.states.value.attachmentErrorPanes)
    }

    private fun TestScope.fixture(attachments: FakeAttachments): AiStudioModelTest.Fixture {
        val support = PromptInputSupport(resourceMediaTypes = setOf("text/markdown"), maxAttachments = 3)
        val repository = object : StudioRepository by InMemoryStudioRepository(TestClock(this)) {
            override fun observeModels() = flowOf(listOf(StudioModel("model", "Model", inputSupport = support)))
        }
        val ready = AiStudioState.Ready(
            panes = listOf(StudioPane(0)),
            focusedPaneId = 0,
            settings = DefaultRunSettings.copy(modelId = "model"),
            defaultProjectId = null,
        )
        return AiStudioModelTest.Fixture(this, ready, repository, attachments = attachments)
    }

    private class FakeAttachments : Machine<AttachmentsState, AttachmentsIntent, AttachmentsOutput> {
        override val name = "attachments"
        override val state = MutableStateFlow<AttachmentsState>(AttachmentsState.Ready)
        override val outputs = MutableSharedFlow<AttachmentsOutput>()
        val sent = mutableListOf<AttachmentsIntent.Public.Import>()
        var result = SendResult.Accepted
        override suspend fun send(intent: AttachmentsIntent): SendResult {
            sent += intent as AttachmentsIntent.Public.Import
            return result
        }
    }
}
