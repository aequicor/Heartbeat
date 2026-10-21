package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructionBounds
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

class StudioHumanAuthorityTest {
    @Test
    fun `restart and native rollover keep the original restrictions before continue`() = runTest {
        val initial = store()
        val restriction = "  Never read core.\nOnly inspect features.  "
        captureStudioUserInstructions(initial, "chat", FIRST, restriction)
        val restarted = ChecklistTestStore(ChatSpec).apply { values.value = initial.values.value }
        val old = assertNotNull(restarted.get(ChatsKey)).single()
        val replacement = SessionRef(EngineId("other"), SessionSourceId("local"), "replacement")
        restarted.set(ChatsKey, listOf(old.copy(ref = replacement, previousRefs = listOf(ORIGINAL))))
        val resumed = assertIs<TrustedUserInstructions.Complete>(
            captureStudioUserInstructions(restarted, "chat", SECOND, "Continue"),
        )
        assertEquals(listOf(restriction, "Continue"), resumed.messages.map { it.text })
        assertEquals(listOf(FIRST, SECOND), resumed.messages.map { it.request })
    }

    @Test
    fun `legacy history cannot gain complete authority from continue`() = runTest {
        val store = store(isComplete = false)
        assertEquals(
            TrustedUserInstructions.Incomplete,
            captureStudioUserInstructions(store, "chat", FIRST, "Continue with the previous task"),
        )
        val restarted = ChecklistTestStore(ChatSpec).apply { values.value = store.values.value }
        assertEquals(
            TrustedUserInstructions.Incomplete,
            captureStudioUserInstructions(restarted, "chat", SECOND, "The earlier instructions still apply"),
        )
        assertFalse(assertNotNull(restarted.get(ChatsKey)?.single()?.humanAuthority).isComplete)
    }

    @Test
    fun `automatic continuations and duplicate deliveries do not append generated text`() = runTest {
        val store = store()
        val first = captureStudioUserInstructions(store, "chat", FIRST, "Only inspect public files")
        assertEquals(first, captureStudioUserInstructions(store, "chat", FIRST, "Only inspect public files"))
        assertEquals(first, captureStudioUserInstructions(store, "chat", SECOND, null))
        val original = assertIs<TrustedUserInstructions.Complete>(first)
        assertEquals(1, original.messages.size)
        assertEquals(FIRST, original.messages.single().request)
    }

    @Test
    fun `conflicting request identity cannot replace a prior restriction`() = runTest {
        val store = store()
        captureStudioUserInstructions(store, "chat", FIRST, "Never delete anything")
        assertEquals(
            TrustedUserInstructions.Incomplete,
            captureStudioUserInstructions(store, "chat", FIRST, "Delete the entire workspace"),
        )
        assertEquals(
            TrustedUserInstructions.Incomplete,
            captureStudioUserInstructions(store, "chat", SECOND, "Continue"),
        )
        assertEquals(
            "Never delete anything",
            store.get(ChatsKey)?.single()?.humanAuthority?.messages?.single()?.text,
        )
    }

    @Test
    fun `oversized authority stays incomplete after restart without truncating known restrictions`() = runTest {
        val store = store()
        captureStudioUserInstructions(store, "chat", FIRST, "Never read secrets")
        val oversized = "x".repeat(TrustedUserInstructionBounds.MAX_TEXT_CHARACTERS + 1)
        assertEquals(
            TrustedUserInstructions.Incomplete,
            captureStudioUserInstructions(store, "chat", SECOND, oversized),
        )
        val restarted = ChecklistTestStore(ChatSpec).apply { values.value = store.values.value }
        assertEquals(
            TrustedUserInstructions.Incomplete,
            captureStudioUserInstructions(restarted, "chat", RequestId("later"), "Continue"),
        )
        assertEquals("Never read secrets", restarted.get(ChatsKey)?.single()?.humanAuthority?.messages?.single()?.text)
    }

    @Test
    fun `questionnaire revocation survives restart and never promotes generated titles or later continue`() = runTest {
        val store = store()
        captureStudioUserInstructions(store, "chat", FIRST, "Never read private files")
        invalidateStudioUserInstructions(store, "chat")
        val restarted = ChecklistTestStore(ChatSpec).apply { values.value = store.values.value }
        assertEquals(
            TrustedUserInstructions.Incomplete,
            captureStudioUserInstructions(restarted, "chat", SECOND, "Continue"),
        )
        assertEquals(
            listOf("Never read private files"),
            restarted.get(ChatsKey)?.single()?.humanAuthority?.messages?.map { it.text },
        )
    }

    @Test
    fun `failed questionnaire revocation persistence does not report success`() = runTest {
        val store = store()
        captureStudioUserInstructions(store, "chat", FIRST, "Inspect the project")
        store.failWrites = true
        assertFailsWith<IllegalStateException> { invalidateStudioUserInstructions(store, "chat") }
        assertEquals(true, store.get(ChatsKey)?.single()?.humanAuthority?.isComplete)
    }

    @Test
    fun `live revocation observes durable incomplete authority and covers the captured native ref`() = runTest {
        val store = store()
        captureStudioUserInstructions(store, "chat", FIRST, "Never read private files")
        val revoked = mutableListOf<SessionRef>()
        val replacement = ORIGINAL.copy(nativeId = "replacement")
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                assertEquals(false, store.get(ChatsKey)?.single()?.humanAuthority?.isComplete)
                revoked += session
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { null }
        authority.invalidate("chat", replacement)
        assertEquals(listOf(replacement, ORIGINAL), revoked)
    }

    @Test
    fun `failed durable revocation never reaches the live authority barrier`() = runTest {
        val store = store()
        store.failWrites = true
        var revoked = false
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                revoked = true
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { null }
        assertFailsWith<IllegalStateException> { authority.invalidate("chat") }
        assertFalse(revoked)
    }

    @Test
    fun `failed persistence never returns the new authority snapshot`() = runTest {
        val store = store()
        store.failWrites = true
        assertFailsWith<IllegalStateException> {
            captureStudioUserInstructions(store, "chat", FIRST, "Read the selected file")
        }
        assertEquals(emptyList<StudioHumanInstruction>(), store.get(ChatsKey)?.single()?.humanAuthority?.messages)
        assertEquals(KeyValueReadPolicy.Strict, ChatSpec.readPolicy)
    }
}

private suspend fun store(isComplete: Boolean = true): ChecklistTestStore = ChecklistTestStore(ChatSpec).apply {
    set(
        ChatsKey,
        listOf(
            StudioChatRecord(
                "chat",
                "Chat",
                Instant.fromEpochSeconds(0),
                ref = ORIGINAL,
                humanAuthority = StudioHumanAuthority().takeIf { isComplete },
            ),
        ),
    )
}

private val ORIGINAL = SessionRef(EngineId("engine"), SessionSourceId("local"), "original")
private val FIRST = RequestId("first")
private val SECOND = RequestId("second")
