package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchResourcesTest {
    private val first = ResearchQuestion("first")
    private val second = ResearchQuestion("second")
    private val target = EngineTarget(EngineId("koog"), EngineBindingId("binding"), ModelId("model"))
    private val session = ResearchSession("session", "", target, listOf(first, second))
    private val source = ResearchResource(
        "source",
        "Example",
        ResearchResourceKind.Website,
        "https://example.com",
        "text/html",
        "SOURCE SECRET",
    )

    @Test
    fun `sources attached to first question become shared regardless requested scope`() {
        val result = session.attach(first.id, source, ResearchResourceScope.Question)
        assertEquals(setOf(source.id), result.sharedResourceIds)
        assertEquals(listOf(source), result.selectedResources(result.questions.last()))
    }

    @Test
    fun `later question resources stay local until explicitly shared`() {
        val result = session.attach(second.id, source, ResearchResourceScope.Question)
        assertTrue(result.sharedResourceIds.isEmpty())
        assertTrue(result.selectedResources(result.questions.first()).isEmpty())
        assertEquals(listOf(source), result.selectedResources(result.questions.last()))
        val shared = result.attach(second.id, source.copy(id = "duplicate"), ResearchResourceScope.Session)
        assertEquals(1, shared.resources.size)
        assertEquals(setOf(source.id), shared.sharedResourceIds)
    }

    @Test
    fun `deselected source is absent from followup context and other question selections are unchanged`() {
        val shared = session.attach(first.id, source, ResearchResourceScope.Session)
        val excluded = shared.questions.last().copy(excludedResourceIds = setOf(source.id))
        val parts = shared.promptParts(excluded, "Follow-up")
        val context = parts.filterIsInstance<ContentPart.Resource>().joinToString("\n") {
            Base64.decode(it.resource.id.substringAfter(',')).decodeToString()
        }
        assertFalse(context.contains(source.text))
        assertFalse(context.contains(source.value))
        assertEquals(listOf(source), shared.selectedResources(shared.questions.first()))
        assertEquals(listOf(ContentPart.Text("Follow-up")), parts.filterIsInstance<ContentPart.Text>())
    }

    @Test
    fun `image and PDF stay native content while text documents are framed resources`() {
        val image = source.copy(
            id = "image",
            kind = ResearchResourceKind.Image,
            value = "https://example.com/a.png",
            mediaType = "image/png",
        )
        val pdf = source.copy(
            id = "pdf",
            kind = ResearchResourceKind.Document,
            value = "data:application/pdf;base64,JVBERg==",
            mediaType = "application/pdf",
        )
        val text = source.copy(
            id = "text",
            kind = ResearchResourceKind.Document,
            value = "Document text",
            mediaType = "text/plain",
            text = "Document text",
        )
        val sources = listOf(image, pdf, text)
        val state = session.copy(resources = sources, sharedResourceIds = sources.map { it.id }.toSet())
        val parts = state.promptParts(first, "Compare")
        assertEquals(
            listOf(ContentPart.Image(ResourceRef(image.value, image.mediaType))),
            parts.filterIsInstance<ContentPart.Image>(),
        )
        assertTrue(parts.contains(ContentPart.Resource(ResourceRef(pdf.value, pdf.mediaType))))
        assertTrue(
            parts.filterIsInstance<ContentPart.Resource>().any { it.resource.id.startsWith("data:text/plain;base64,") },
        )
    }

    @Test
    fun `only successful structured search output produces sources`() {
        val callId = ToolCallId("search")
        val call = SessionItem.ToolCall(info("call", 0), callId, "web_search", "{}", ToolCallStatus.Succeeded)
        val result = SessionItem.ToolResult(
            info("result", 1),
            callId,
            listOf(ContentPart.Text("""[{"url":"https://example.com","title":"Result","snippet":"Context"}]""")),
        )
        val answer = SessionItem.Message(
            info("reply", 2),
            MessageRole.Assistant,
            listOf(ContentPart.Text("https://unverified.example")),
        )
        val discovered = discoveredResources(listOf(call, result, answer))
        assertEquals(listOf("https://example.com"), discovered.map { it.value })
        assertEquals("", discovered.single().text)
        assertTrue(discoveredResources(listOf(call.copy(status = ToolCallStatus.Failed), result)).isEmpty())
    }

    @Test
    fun `projected user history drops bytes and followups keep only visible conversation`() {
        val message = SessionItem.Message(
            info("user", 0),
            MessageRole.User,
            listOf(
                ContentPart.Text("Question"),
                ContentPart.Image(ResourceRef("data:image/png;base64,AAAA", "image/png")),
            ),
        )
        val projected = message.withoutAttachments() as SessionItem.Message
        assertEquals(listOf(ContentPart.Text("Question")), projected.parts)
        val parts = session.promptParts(first.copy(items = listOf(projected)), "Next question")
        val references = parts.filterIsInstance<ContentPart.Resource>().joinToString("\n") {
            Base64.decode(it.resource.id.substringAfter(',')).decodeToString()
        }
        assertTrue(references.contains("User: Question"))
        assertFalse(references.contains("AAAA"))
    }

    @Test
    fun `saved answer preserves exposed reasoning without replaying it as user context`() {
        val answer = SessionItem.Message(
            info("reply", 0),
            MessageRole.Assistant,
            listOf(
                ContentPart.Reasoning("EXPOSED REASONING"),
                ContentPart.Text("Visible answer"),
                ContentPart.Image(ResourceRef("data:image/png;base64,AAAA", "image/png")),
            ),
        )
        val saved = answer.withoutAttachments() as SessionItem.Message
        assertEquals(
            listOf(ContentPart.Reasoning("EXPOSED REASONING"), ContentPart.Text("Visible answer")),
            saved.parts,
        )
        val context = session.promptParts(first.copy(items = listOf(saved)), "Continue")
            .filterIsInstance<ContentPart.Resource>().joinToString("\n") {
                Base64.decode(it.resource.id.substringAfter(',')).decodeToString()
            }
        assertTrue(context.contains("Visible answer"))
        assertFalse(context.contains("EXPOSED REASONING"))
        assertFalse(context.contains("AAAA"))
    }

    @Test
    fun `reusing discovered website loads full body before supplying source to another question`() = runTest {
        val unloaded = source.copy(text = "")
        val state = session.attach(first.id, unloaded, ResearchResourceScope.Question)
        val search = SourceSearch("Full website evidence")
        val loaded = loadSelectedSources(state, second, search)
        assertEquals(listOf(source.value), search.fetched)
        assertEquals("Full website evidence", loaded.resources.single().text)
        loadSelectedSources(loaded, second, search)
        assertEquals(1, search.fetched.size)
    }

    @Test
    fun `excluded discovery is not fetched and unavailable website never supplies empty evidence`() = runTest {
        val state = session.attach(first.id, source.copy(text = ""), ResearchResourceScope.Question)
        val search = SourceSearch("")
        loadSelectedSources(state, second.copy(excludedResourceIds = setOf(source.id)), search)
        assertTrue(search.fetched.isEmpty())
        assertFailsWith<IllegalArgumentException> { loadSelectedSources(state, second, search) }
    }

    @Test
    fun `recovery retains streamed output newer than native acceptance checkpoint`() {
        val user = SessionItem.Message(info("user", 0), MessageRole.User, listOf(ContentPart.Text("Question")))
        val streamed = SessionItem.Message(
            info("assistant", 1).copy(revision = 4),
            MessageRole.Assistant,
            listOf(ContentPart.Text("Partial answer")),
        )
        assertEquals(listOf(user, streamed), mergeResearchSegment(listOf(user, streamed), listOf(user)))
        val finished = streamed.copy(
            info = streamed.info.copy(revision = 5),
            parts = listOf(ContentPart.Text("Full answer")),
        )
        assertEquals(listOf(user, finished), mergeResearchSegment(listOf(user, streamed), listOf(user, finished)))
    }

    @Test
    fun `fetched source body wins over earlier search candidate with the same URL`() {
        val searchId = ToolCallId("search")
        val fetchId = ToolCallId("fetch")
        val search = SessionItem.ToolCall(info("search", 0), searchId, "web_search", "{}", ToolCallStatus.Succeeded)
        val fetch = SessionItem.ToolCall(info("fetch", 2), fetchId, "web_fetch", "{}", ToolCallStatus.Succeeded)
        val candidate = SessionItem.ToolResult(
            info("candidate", 1),
            searchId,
            listOf(ContentPart.Text("""[{"url":"https://example.com","title":"Site","snippet":"Preview"}]""")),
        )
        val body = SessionItem.ToolResult(
            info("body", 3),
            fetchId,
            listOf(ContentPart.Text("""{"url":"https://example.com","title":"Site","content":"Full evidence"}""")),
        )
        val resources = discoveredResources(listOf(search, candidate, fetch, body))
        assertEquals(1, resources.size)
        assertEquals("Full evidence", resources.single().text)
    }

    @Test
    fun `fresh shorter page replaces old text while a search candidate preserves fetched content`() {
        val initial = session.attach(first.id, source, ResearchResourceScope.Session)
        val refreshed = initial.attach(
            first.id,
            source.copy(id = "fresh", text = "New"),
            ResearchResourceScope.Session,
        )
        assertEquals("New", refreshed.resources.single().text)
        assertEquals(source.id, refreshed.resources.single().id)
        val candidate = refreshed.attach(
            second.id,
            source.copy(id = "candidate", text = ""),
            ResearchResourceScope.Question,
        )
        assertEquals("New", candidate.resources.single().text)
    }

    @Test
    fun `latest successful fetch replaces longer previous body for same URL`() {
        fun fetch(id: String, position: Long, text: String): List<SessionItem> {
            val call = ToolCallId(id)
            return listOf(
                SessionItem.ToolCall(info(id, position), call, "web_fetch", "{}", ToolCallStatus.Succeeded),
                SessionItem.ToolResult(
                    info("$id-result", position + 1),
                    call,
                    listOf(ContentPart.Text("""{"url":"https://example.com","content":"$text"}""")),
                ),
            )
        }
        val result = discoveredResources(fetch("old", 0, "Long outdated content") + fetch("new", 2, "New"))
        assertEquals("New", result.single().text)
    }

    private fun info(id: String, position: Long) = ItemInfo(ItemId(id), position, 0)
}

private class SourceSearch(private val body: String) : SearchEngine {
    val fetched = mutableListOf<String>()
    override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> = error(
        "Unused",
    )
    override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent {
        fetched += url
        return ResourceContent(url, "Full page", body)
    }
}
