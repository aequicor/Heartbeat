package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogResourceTest {
    @Test
    fun `image and document inputs survive a native session resume without becoming visible prompt text`() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val parts = listOf(
            ContentPart.Text("Compare the evidence"),
            ContentPart.Image(ResourceRef("data:image/png;base64,aW1hZ2U=", "image/png")),
            ContentPart.Resource(ResourceRef("data:text/plain;base64,c291cmNl", "text/plain")),
        )
        session.features.require(SendsPrompts).send(PromptRequest(RequestId("resource"), parts))
        f.executor.complete()
        runCurrent()
        val history = session.features.require(SessionHistory).page().items
        assertEquals(parts, history.filterIsInstance<SessionItem.Message>().first().parts)
        f.runtime().close()
        val resumed = f.runtime().attach(session.ref, ResumeSessionRequest(f.target))
        resumed.features.require(SendsPrompts).send(f.request("followup"))
        f.executor.complete()
        runCurrent()
        val prompt = f.executor.prompts.last()
        assertEquals(KOOG_RESOURCE_BOUNDARY, assertIs<Message.System>(prompt.messages.first()).textContent())
        val user = prompt.messages.filterIsInstance<Message.User>().first()
        val image = assertIs<AttachmentSource.Image>(
            user.parts.filterIsInstance<MessagePart.Attachment>().single().source,
        )
        assertEquals("aW1hZ2U=", assertIs<AttachmentContent.Binary.Base64>(image.content).base64)
        assertTrue(user.textContent().contains("source"))
        assertEquals(2, prompt.messages.filterIsInstance<Message.User>().size)
    }

    @Test
    fun `cloud images retain their URL and PDF documents become binary native attachments`() {
        KoogProvider.entries.filter { it != KoogProvider.Ollama }.forEach { provider ->
            val parts = listOf(
                ContentPart.Image(ResourceRef("https://example.com/image", "image/jpeg")),
                ContentPart.Resource(ResourceRef("data:application/pdf;base64,JVBERi0=", "application/pdf")),
            ).koogUserParts(provider)
            val image = assertIs<AttachmentSource.Image>(assertIs<MessagePart.Attachment>(parts.first()).source)
            assertEquals("https://example.com/image", assertIs<AttachmentContent.URL>(image.content).url)
            val pdf = assertIs<AttachmentSource.File>(assertIs<MessagePart.Attachment>(parts.last()).source)
            assertEquals("application/pdf", pdf.mimeType)
            assertEquals("JVBERi0=", assertIs<AttachmentContent.Binary.Base64>(pdf.content).base64)
        }
    }

    @Test
    fun `invalid references and modalities are rejected before any acceptance or provider request`() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val invalid = listOf(
            ContentPart.Image(ResourceRef("file:///private/image.png", "image/png")),
            ContentPart.Image(ResourceRef("https://example.com/image", "image/png")),
            ContentPart.Image(ResourceRef("data:image/png;base64,%%%", "image/png")),
            ContentPart.Image(ResourceRef("data:image/jpeg;base64,aW1hZ2U=", "image/png")),
            ContentPart.Image(ResourceRef("data:image/svg+xml;base64,aW1hZ2U=", "image/svg+xml")),
            ContentPart.Resource(ResourceRef("data:application/pdf;base64,JVBERi0=", "application/pdf")),
            ContentPart.Resource(ResourceRef("https://example.com/document", "text/plain")),
            ContentPart.Resource(ResourceRef("data:text/plain;base64,/w==", "text/plain")),
        )
        invalid.forEachIndexed { index, part ->
            val request = PromptRequest(RequestId("invalid-$index"), listOf(part))
            val failure = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
            assertEquals(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id), failure.failure)
        }
        assertEquals(0, f.opens)
        assertTrue(session.features.require(SessionHistory).page().items.isEmpty())
    }

    @Test
    fun `successful search and fetch results expose structured resource references`() = runTest {
        val f = KoogTestFixture(this)
        f.searchResults = listOf(SearchResult("https://example.com/found", "Found", "Snippet"))
        f.fetchedResource = ResourceContent("https://example.com/final", "Final", "Body")
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        f.executor.frames.trySend(StreamFrame.ToolCallComplete("s", "web_search", """{"query":"topic"}""", 0))
        f.executor.frames.trySend(
            StreamFrame.ToolCallComplete("f", "web_fetch", """{"url":"https://example.com"}""", 1),
        )
        f.executor.frames.trySend(StreamFrame.End("tool_calls"))
        f.executor.complete()
        runCurrent()
        val results = session.features.require(SessionHistory).page().items.filterIsInstance<SessionItem.ToolResult>()
        assertEquals(
            listOf("https://example.com/found", "https://example.com/final"),
            results.flatMap { it.parts.filterIsInstance<ContentPart.Resource>() }.map { it.resource.id },
        )
        assertEquals(KOOG_RESOURCE_BOUNDARY, f.executor.prompts.last().messages.first().textContent())
    }

    @Test
    fun `failed fetch never exposes a resource reference`() = runTest {
        val f = KoogTestFixture(this)
        val result = executeKoogSearch(f.search, StreamFrame.ToolCallComplete("f", "web_fetch", "{}", 0))
        assertTrue(result.isFailed)
        assertTrue(result.resources.isEmpty())
    }
}
