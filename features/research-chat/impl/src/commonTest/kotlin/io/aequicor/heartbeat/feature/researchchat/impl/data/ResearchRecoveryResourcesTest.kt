package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResearchRecoveryResourcesTest {
    private val source = ResearchResource(
        "source",
        "Evidence",
        ResearchResourceKind.Website,
        "https://example.com/source",
        "text/html",
        text = "Verified evidence",
    )
    private val question = ResearchQuestion("question")
    private val session = ResearchSession(
        "session",
        "",
        EngineTarget(EngineId("koog"), EngineBindingId("binding"), ModelId("model")),
        listOf(question),
        listOf(source),
        setOf(source.id),
    )

    @Test
    fun `failed fetch creates a retryable source only with recovery enabled`() {
        val call = failedCall("""{"url":"${source.value}"}""")
        assertTrue(discoveredResources(listOf(call)).isEmpty())
        val failed = discoveredResources(listOf(call), recoverFailures = true).single()
        assertEquals(source.value, failed.value)
        assertEquals(ResearchLoadState.Failed, failed.loadState)
        assertEquals("fetch_failed", failed.loadError)
        assertEquals("", failed.text)
    }

    @Test
    fun `failed search cancellation and prose error addresses do not create sources`() {
        val valid = failedCall("""{"url":"${source.value}"}""")
        val inputs = listOf(
            valid.copy(name = "web_search"),
            valid.copy(status = ToolCallStatus.Cancelled),
            failedCall("""{"error":"Cannot read ${source.value}"}"""),
            failedCall("""{"url":"file:///private"}"""),
        )
        assertTrue(discoveredResources(inputs, recoverFailures = true).isEmpty())
    }

    @Test
    fun `replayed history reports a malformed failed fetch once per segment`() {
        val malformed = failedCall("not json")
        val reported = mutableSetOf<ToolCallId>()
        repeat(2) {
            assertTrue(discoveredResources(listOf(malformed), recoverFailures = true, reported).isEmpty())
        }
        assertEquals(setOf(malformed.call), reported)
    }

    @Test
    fun `failed refresh preserves ready evidence and its catalog identity`() {
        val failed = source.copy(
            id = "retry",
            text = "",
            loadState = ResearchLoadState.Failed,
            loadError = "fetch_failed",
        )
        val merged = session.attach(question.id, failed, ResearchResourceScope.Session)
        assertEquals(listOf(source), merged.resources)
        assertEquals(setOf(source.id), merged.sharedResourceIds)
    }

    @Test
    fun `rediscovered source cannot replace the downloaded document with a failure`() {
        val document = source.copy(
            kind = ResearchResourceKind.Document,
            value = "attachment:pdf",
            sourceUrl = source.value,
            mediaType = "application/pdf",
            attachmentId = AttachmentId("pdf"),
            text = "",
        )
        val failed = source.copy(id = "retry", text = "", loadState = ResearchLoadState.Failed)
        val merged = session.copy(resources = listOf(document))
            .attach(question.id, failed, ResearchResourceScope.Session)
        assertEquals(listOf(document), merged.resources)
    }

    @Test
    fun `downloaded source cites original URL while passing registered bytes to the engine`() {
        val document = source.copy(
            kind = ResearchResourceKind.Document,
            value = "attachment:pdf",
            sourceUrl = source.value,
            mediaType = "application/pdf",
            attachmentId = AttachmentId("pdf"),
            text = "",
        )
        val parts = session.copy(resources = listOf(document)).promptParts(question, "Summarize")
        assertTrue(ContentPart.Resource(ResourceRef(document.value, document.mediaType)) in parts)
        val context = parts.filterIsInstance<ContentPart.Resource>()
            .filter { it.resource.id.startsWith("data:text/plain;base64,") }
            .joinToString("\n") { Base64.decode(it.resource.id.substringAfter(',')).decodeToString() }
        assertTrue(context.contains("URL: ${source.value}"))
    }

    @Test
    fun `late completion cannot overwrite cancellation another attempt or a different resource`() {
        val pending = source.copy(loadState = ResearchLoadState.Loading, recoveryAttempt = "first")
        val completed = pending.copy(loadState = ResearchLoadState.Ready, recoveryAttempt = null, text = "New")
        val cancelled = pending.copy(loadState = ResearchLoadState.Failed, recoveryAttempt = null)
        val retried = pending.copy(recoveryAttempt = "second")
        val unrelated = pending.copy(id = "different")
        assertEquals(completed, pending.applyRecoveryUpdate(pending, completed))
        assertEquals(cancelled, cancelled.applyRecoveryUpdate(pending, completed))
        assertEquals(retried, retried.applyRecoveryUpdate(pending, completed))
        assertEquals(unrelated, unrelated.applyRecoveryUpdate(pending, completed))
    }

    @Test
    fun `recovery start cannot replace newer ready evidence with the same null attempt`() {
        val failed = source.copy(text = "", loadState = ResearchLoadState.Failed)
        val pending = failed.copy(loadState = ResearchLoadState.Loading, recoveryAttempt = "new")
        assertEquals(pending, failed.applyRecoveryUpdate(failed, pending))
        assertEquals(source, source.applyRecoveryUpdate(failed, pending))
    }

    @Test
    fun `late recovery failure preserves concurrent source hydration even with the same attempt`() {
        val pending = source.copy(text = "", loadState = ResearchLoadState.Loading, recoveryAttempt = "same")
        val hydrated = pending.copy(text = "Verified evidence", loadState = ResearchLoadState.Ready)
        val failed = pending.copy(loadState = ResearchLoadState.NeedsUserAction, loadError = "browser_retry_required")
        assertEquals(hydrated, hydrated.applyRecoveryUpdate(pending, failed))
    }

    @Test
    fun `challenge and empty captures do not become evidence`() {
        val normal = buildJsonObject {
            put("title", "Article")
            put("content", "Evidence")
        }
        assertEquals("Evidence", readableRecoveryContent(normal))
        val rejected = listOf(
            buildJsonObject { put("content", "") },
            buildJsonObject {
                put("content", "Evidence")
                put("requiresUserAction", true)
            },
            buildJsonObject {
                put("title", "Just a moment")
                put("content", "Checking")
            },
            buildJsonObject { put("content", "Please verify you are human") },
        )
        rejected.forEach { assertFailsWith<IllegalArgumentException> { readableRecoveryContent(it) } }
    }

    private fun failedCall(arguments: String): SessionItem.ToolCall = SessionItem.ToolCall(
        ItemInfo(ItemId("call"), 0, 0),
        ToolCallId("fetch"),
        "web_fetch",
        arguments,
        ToolCallStatus.Failed,
    )
}
