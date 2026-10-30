package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstruction
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructionBounds
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

class StudioUserInstructionsTest {
    @Test
    fun `reading captured originals is exact and never writes`() = runTest {
        val store = ChecklistTestStore(ChatSpec)
        store.set(ChatsKey, listOf(record(StudioHumanAuthority())))
        val original = "  Never delete files.\nRead only.  "
        captureStudioUserInstructions(store, "chat", RequestId("request"), original)
        val before = store.values.value
        store.failWrites = true
        val snapshot = assertIs<TrustedUserInstructions.Complete>(readStudioUserInstructions(store, "chat"))
        assertEquals(listOf(TrustedUserInstruction(RequestId("request"), original)), snapshot.messages)
        assertEquals(before, store.values.value)
    }

    @Test
    fun `missing legacy and duplicate records remain incomplete without repair`() = runTest {
        val store = ChecklistTestStore(ChatSpec)
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        listOf(
            listOf(record(null)),
            listOf(record(StudioHumanAuthority()).copy(id = "other")),
            listOf(record(StudioHumanAuthority()), record(StudioHumanAuthority())),
        ).forEach { records ->
            store.set(ChatsKey, records)
            val before = store.values.value
            assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
            assertEquals(before, store.values.value)
        }
    }

    @Test
    fun `empty new chat is complete but grants no human text`() = runTest {
        val store = ChecklistTestStore(ChatSpec)
        store.set(ChatsKey, listOf(record(StudioHumanAuthority())))
        assertEquals(TrustedUserInstructions.Complete(emptyList()), readStudioUserInstructions(store, "chat"))
    }

    @Test
    fun `persisted revocation remains incomplete after restart and further human input`() = runTest {
        val store = ChecklistTestStore(ChatSpec)
        store.set(ChatsKey, listOf(record(StudioHumanAuthority())))
        captureStudioUserInstructions(store, "chat", RequestId("first"), "Never read secrets")
        val original = assertIs<TrustedUserInstructions.Complete>(readStudioUserInstructions(store, "chat"))
        invalidateStudioUserInstructions(store, "chat")
        val restarted = ChecklistTestStore(ChatSpec).apply { values.value = store.values.value }
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(restarted, "chat"))
        captureStudioUserInstructions(restarted, "chat", RequestId("second"), "Continue")
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(restarted, "chat"))
        assertEquals("Never read secrets", original.messages.single().text)
    }

    @Test
    fun `invalid persisted authority cannot become complete on read`() = runTest {
        val instruction = StudioHumanInstruction(RequestId("first"), "Never read secrets")
        val store = ChecklistTestStore(ChatSpec)
        listOf(
            StudioHumanAuthority(listOf(instruction), isComplete = false),
            StudioHumanAuthority(listOf(instruction, instruction)),
            StudioHumanAuthority(
                listOf(instruction.copy(text = "x".repeat(TrustedUserInstructionBounds.MAX_TEXT_CHARACTERS + 1))),
            ),
            StudioHumanAuthority(
                List(TrustedUserInstructionBounds.MAX_MESSAGES + 1) { index ->
                    StudioHumanInstruction(RequestId("$index"), "text")
                },
            ),
        ).forEach { authority ->
            store.set(ChatsKey, listOf(record(authority)))
            assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        }
    }

    @Test
    fun `corrupt storage fails closed without replacing persisted data`() = runTest {
        val store = ChecklistTestStore(ChatSpec)
        store.values.value = mapOf(ChatsKey.name to "invalid json")
        val before = store.values.value
        assertFailsWith<IllegalArgumentException> { readStudioUserInstructions(store, "chat") }
        assertEquals(before, store.values.value)
    }
}

private fun record(authority: StudioHumanAuthority?): StudioChatRecord = StudioChatRecord(
    "chat",
    "Chat",
    Instant.DISTANT_PAST,
    humanAuthority = authority,
)
