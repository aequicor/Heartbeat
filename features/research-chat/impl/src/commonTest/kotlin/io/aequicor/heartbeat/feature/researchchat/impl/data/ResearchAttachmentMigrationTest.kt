package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchAttachmentMigrationTest {
    @Test
    fun `rejected legacy file preserves the rest of the workspace`() = runTest {
        val legacy = ResearchResource(
            "broken",
            "Bad source",
            ResearchResourceKind.Document,
            "OLD CONTENT",
            "text/plain",
        )
        val good = legacy.copy(id = "good", value = "Valid text")
        val attachments = object : ResearchAttachments {
            override suspend fun persist(
                source: ResearchResource,
                support: PromptInputSupport,
                migration: Boolean,
            ): ResearchResource {
                if (source.id == legacy.id) throw IllegalArgumentException("Invalid historical content")
                return source.copy(value = "attachment:good", attachmentId = AttachmentId("good"))
            }
            override suspend fun registered(id: AttachmentId): AttachmentDescriptor = error("Unused")
            override suspend fun import(
                inputs: List<AttachmentInput>,
                support: PromptInputSupport,
            ): List<AttachmentDescriptor> = error("Unused")
        }
        val fixture = ResearchExecutionFixture(backgroundScope, attachments)
        val target = EngineTarget(KoogEngineId, EngineBindingId("binding"), ModelId("model"))
        fixture.storage.add(
            ResearchSession("saved", "", target, listOf(ResearchQuestion("first")), listOf(legacy, good)),
        )
        val opened = fixture.repository.prepare(target).sessions.single()
        assertEquals(legacy.copy(hasAttachmentError = true), opened.resources.first())
        assertEquals(AttachmentId("good"), opened.resources.last().attachmentId)
        fixture.repository.removeResource(opened.id, legacy.id)
        assertEquals(listOf("good"), fixture.repository.prepare(target).sessions.single().resources.map { it.id })
    }

    @Test
    fun `opening legacy sources migrates once while source ids and question selection survive`() = runTest {
        val imported = mutableListOf<String>()
        val attachments = object : ResearchAttachments {
            override suspend fun persist(
                source: ResearchResource,
                support: PromptInputSupport,
                migration: Boolean,
            ): ResearchResource {
                if (source.kind == ResearchResourceKind.Website || source.attachmentId != null) return source
                assertTrue(migration)
                imported += source.id
                return source.copy(value = "attachment:${source.id}", text = "", attachmentId = AttachmentId(source.id))
            }
            override suspend fun registered(id: AttachmentId): AttachmentDescriptor = error("Unused")
            override suspend fun import(
                inputs: List<AttachmentInput>,
                support: PromptInputSupport,
            ): List<AttachmentDescriptor> = error("Unused")
        }
        val fixture = ResearchExecutionFixture(backgroundScope, attachments)
        val target = EngineTarget(KoogEngineId, EngineBindingId("binding"), ModelId("model"))
        val first = ResearchQuestion("first")
        val second = ResearchQuestion("second", excludedResourceIds = setOf("text"), resourceIds = setOf("image"))
        val sources = listOf(
            ResearchResource("text", "Notes", ResearchResourceKind.Document, "original text", "text/plain"),
            ResearchResource("image", "Image", ResearchResourceKind.Image, "data:image/png;base64,AAAA", "image/png"),
            ResearchResource(
                "web",
                "Website",
                ResearchResourceKind.Website,
                "https://example.com",
                "text/html",
                "body",
            ),
        )
        fixture.storage.add(ResearchSession("session", "", target, listOf(first, second), sources, setOf("text")))
        fixture.repository.prepare(target)
        fixture.repository.prepare(target)
        val saved = fixture.storage.read().single()
        assertEquals(listOf("text", "image"), imported)
        assertEquals(sources.map { it.id }, saved.resources.map { it.id })
        assertEquals(listOf(first, second), saved.questions)
        assertEquals(setOf("text"), saved.sharedResourceIds)
        assertEquals(listOf("image"), saved.selectedResources(second).map { it.id })
        assertEquals(sources.last(), saved.resources.last())
        assertTrue(saved.resources.take(2).all { it.value.startsWith("attachment:") && it.text.isEmpty() })
    }
}
