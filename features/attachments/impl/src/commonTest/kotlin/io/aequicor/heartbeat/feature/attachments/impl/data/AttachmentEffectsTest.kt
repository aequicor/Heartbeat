package io.aequicor.heartbeat.feature.attachments.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentImportPurpose
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEffect
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentEffectsTest {
    @Test
    fun `disabled toggle forbids new files but allows migrating already saved sources`() = runTest {
        val storage = FakeStorage()
        val effects = AttachmentEffects(storage, Toggles(false))
        val scope = RecordingScope()
        val input = listOf(AttachmentInput.Bytes("old.txt", "text/plain", byteArrayOf(65)))
        effects.handle(AttachmentsEffect.Import("new", input, PromptInputSupport.TextDocuments), scope)
        assertEquals(0, storage.imports)
        assertEquals(
            listOf<AttachmentsIntent>(AttachmentsIntent.Internal.Failed("new", AttachmentFailure.Disabled)),
            scope.sent,
        )
        effects.handle(
            AttachmentsEffect.Import(
                "old",
                input,
                PromptInputSupport.TextDocuments,
                AttachmentImportPurpose.Migration,
                "research:1",
            ),
            scope,
        )
        assertEquals(1, storage.imports)
        assertEquals("research:1", storage.lastKey)
    }

    @Test
    fun `preparation and imported results follow completed storage writes`() = runTest {
        val storage = FakeStorage()
        val effects = AttachmentEffects(storage, Toggles(true))
        val scope = RecordingScope()
        effects.handle(AttachmentsEffect.Prepare, scope)
        assertEquals(true, storage.prepared)
        effects.handle(
            AttachmentsEffect.Import(
                "r",
                listOf(AttachmentInput.Bytes("notes.txt", "text/plain", byteArrayOf(65))),
                PromptInputSupport.TextDocuments,
            ),
            scope,
        )
        assertEquals(
            listOf<AttachmentsIntent>(
                AttachmentsIntent.Internal.Prepared,
                AttachmentsIntent.Internal.Imported("r", listOf(storage.item)),
            ),
            scope.sent,
        )
    }

    private class FakeStorage : AttachmentStorage {
        val item = AttachmentDescriptor(AttachmentId("a"), "notes.txt", "text/plain", 1)
        var imports = 0
        var prepared = false
        var lastKey: String? = null
        override suspend fun prepare() {
            prepared = true
        }
        override suspend fun import(
            inputs: List<AttachmentInput>,
            support: PromptInputSupport,
            deduplicationKey: String?,
        ): List<AttachmentDescriptor> {
            imports++
            lastKey = deduplicationKey
            return listOf(item)
        }
        override suspend fun read(id: AttachmentId): ResolvedResource = error("Not used")
    }

    private class Toggles(private val enabled: Boolean) : FeatureToggles {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(enabled as T)

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = enabled as T
    }

    private class RecordingScope : EffectScope<AttachmentsIntent> {
        val sent = mutableListOf<AttachmentsIntent>()
        override suspend fun send(intent: AttachmentsIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
