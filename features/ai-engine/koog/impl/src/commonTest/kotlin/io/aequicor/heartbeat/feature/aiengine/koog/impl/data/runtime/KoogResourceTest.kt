package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
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
        f.modelSupportsImages = true
        f.resources = mapOf(
            "attachment:image" to ResolvedResource("image.png", "image/png", "image".encodeToByteArray()),
            "attachment:source" to ResolvedResource("source.txt", "text/plain", "source".encodeToByteArray()),
        )
        val session = f.session()
        val parts = listOf(
            ContentPart.Text("Compare the evidence"),
            ContentPart.Image(ResourceRef("attachment:image", "image/png")),
            ContentPart.Resource(ResourceRef("attachment:source", "text/plain")),
        )
        session.features.require(SendsPrompts).send(PromptRequest(RequestId("resource"), parts))
        f.executor.frames.trySend(StreamFrame.ReasoningDelta(text = "Thinking", summary = null, index = 0)).getOrThrow()
        f.executor.complete()
        runCurrent()
        val history = session.features.require(SessionHistory).page().items
        assertEquals(parts, history.filterIsInstance<SessionItem.Message>().first().parts)
        assertTrue(
            history.filterIsInstance<SessionItem.Message>().any { ContentPart.Reasoning("Thinking") in it.parts },
        )
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
        assertEquals("answer", prompt.messages.filterIsInstance<Message.Assistant>().single().textContent())
        assertEquals(2, prompt.messages.filterIsInstance<Message.User>().size)
    }

    @Test
    fun `ten owned documents plus generated research context accept but eleven documents reject`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.resources = (0..10).associate { index ->
            "attachment:file-$index" to ResolvedResource("file.txt", "text/plain", "evidence".encodeToByteArray())
        }
        val documents = (0..10).map { index ->
            ContentPart.Resource(ResourceRef("attachment:file-$index", "text/plain"))
        }
        val framing = List(12) {
            ContentPart.Resource(ResourceRef("data:text/plain;base64,Y29udGV4dA==", "text/plain"))
        }
        val session = fixture.session()
        session.features.require(SendsPrompts).send(PromptRequest(RequestId("research"), documents.take(10) + framing))
        fixture.executor.complete()
        runCurrent()
        val original = assertIs<SessionItem.Message>(session.features.require(SessionHistory).page().items.first())
        assertEquals(documents.take(10) + framing, original.parts)
        val request = PromptRequest(RequestId("too-many"), documents + framing)
        val error = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id), error.failure)
    }

    @Test
    fun `native acceptance retains managed file provenance at exactly twenty five MiB`() = runTest {
        val fixture = KoogTestFixture(this)
        val megabyte = 1024 * 1024
        fixture.resources = listOf(10, 10, 5).mapIndexed { index, size ->
            "attachment:file-$index" to ResolvedResource("file.txt", "text/plain", ByteArray(size * megabyte))
        }.toMap() + ("attachment:excess" to ResolvedResource("extra.txt", "text/plain", byteArrayOf(1)))
        val documents = (0..2).map { index ->
            ContentPart.Resource(ResourceRef("attachment:file-$index", "text/plain"))
        }
        val context = ContentPart.Resource(ResourceRef("data:text/plain;base64,Y29udGV4dA==", "text/plain"))
        val parts = documents + context
        val session = fixture.session()
        val accepted = session.features.require(SendsPrompts).send(PromptRequest(RequestId("exact-limit"), parts))
        fixture.executor.complete()
        runCurrent()
        val history = session.features.require(SessionHistory).page().items
        assertEquals(accepted, history.first().info.turn)
        assertEquals(parts, assertIs<SessionItem.Message>(history.first()).parts)
        val excessive = parts + ContentPart.Resource(ResourceRef("attachment:excess", "text/plain"))
        val request = PromptRequest(RequestId("over-limit"), excessive)
        val error = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id), error.failure)
    }

    @Test
    fun `Anthropic advertises raw headroom and rejects eight MiB images before acceptance`() = runTest {
        val fixture = KoogTestFixture(this)
        val source = AuthSource.ManagedKey(
            fixture.source.info,
            AuthScope(KoogProvider.Anthropic.id, KoogProvider.Anthropic.origin),
            AuthSecretId("test-key"),
        )
        val connection = KoogConnection(fixture.binding, source)
        fixture.connections.put(connection)
        val support = fixture.access.inputs.remember(
            connection,
            LLModel(LLMProvider.Anthropic, "test-model", listOf(LLMCapability.Vision.Image)),
        )
        assertEquals(7_340_032L, support.maxFileBytes)
        assertEquals(20_971_520L, support.maxTotalBytes)
        fixture.resources = mapOf(
            "attachment:large" to ResolvedResource("image.png", "image/png", ByteArray(8 * 1024 * 1024)),
        )
        val original = ContentPart.Image(ResourceRef("attachment:large", "image/png"))
        val request = PromptRequest(RequestId("large-image"), listOf(original))
        val session = fixture.session()
        val error = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id), error.failure)
        assertEquals(listOf(original), request.parts)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        assertTrue(session.features.require(SessionHistory).page().items.isEmpty())
        assertEquals(0, fixture.opens)
    }

    @Test
    fun `Anthropic escaped document bytes are counted before native acceptance`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.connections.put(
            KoogConnection(
                fixture.binding,
                AuthSource.ManagedKey(
                    fixture.source.info,
                    AuthScope(KoogProvider.Anthropic.id, KoogProvider.Anthropic.origin),
                    AuthSecretId("test-key"),
                ),
            ),
        )
        // Six MiB of NUL is valid UTF-8 but expands beyond the 32 MB API body limit after JSON escaping.
        fixture.resources = mapOf(
            "attachment:escaped" to ResolvedResource("file.txt", "text/plain", ByteArray(6 * 1024 * 1024)),
        )
        val request = PromptRequest(
            RequestId("escaped"),
            listOf(
                ContentPart.Resource(ResourceRef("attachment:escaped", "text/plain")),
            ),
        )
        val session = fixture.session()
        val error = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id), error.failure)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        assertTrue(session.features.require(SessionHistory).page().items.isEmpty())
        assertTrue(fixture.executor.prompts.isEmpty())
    }

    @Test
    fun `compatible servers cannot accept images through static vendor SDK model metadata`() = runTest {
        listOf(KoogProvider.OpenAICompatible, KoogProvider.AnthropicCompatible).forEach { provider ->
            val fixture = KoogTestFixture(this)
            fixture.modelSupportsImages = true
            fixture.connections.put(
                KoogConnection(
                    fixture.binding,
                    AuthSource.ManagedKey(
                        fixture.source.info,
                        AuthScope(provider.id, EndpointOrigin("https://custom.example")),
                        AuthSecretId("test-key"),
                    ),
                ),
            )
            fixture.resources = mapOf(
                "attachment:image" to ResolvedResource("image.png", "image/png", "image".encodeToByteArray()),
            )
            val request = PromptRequest(
                RequestId("unconfirmed-image"),
                listOf(ContentPart.Image(ResourceRef("attachment:image", "image/png"))),
            )
            val session = fixture.session()
            val error = assertFailsWith<EngineException> { session.features.require(SendsPrompts).send(request) }
            assertEquals(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id), error.failure)
            assertIs<ActiveSessionState.Ready>(session.state.value)
            assertTrue(session.features.require(SessionHistory).page().items.isEmpty())
            assertTrue(fixture.executor.prompts.isEmpty())
        }
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
