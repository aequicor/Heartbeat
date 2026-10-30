package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructionBounds
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionnaireHumanContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

class StudioHumanAuthorityContextTest {
    @Test
    fun `legacy nullable context inherits authority without reviving revoked originals`() {
        listOf("", ",\"isQuestionnaireContextComplete\":null").forEach { field ->
            listOf(true, false).forEach { complete ->
                val authority = Json.decodeFromString<StudioHumanAuthority>("{\"isComplete\":$complete$field}")
                assertEquals(null, authority.isQuestionnaireContextComplete)
                if (complete) {
                    assertIs<StudioQuestionnaireHumanContext.Complete>(authority.questionnaireContextSnapshot())
                    assertIs<TrustedUserInstructions.Complete>(authority.snapshot())
                } else {
                    assertClosed(authority)
                    assertClosed(authority.invalidate(preserveQuestionnaireContext = true).append(FIRST, "Continue"))
                }
            }
        }
    }

    @Test
    fun `missing legacy authority and unknown chat never gain questionnaire context`() = runTest {
        val store = contextStore(null)
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, readStudioQuestionnaireHumanContext(store, "missing"))
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, readStudioQuestionnaireHumanContext(store, "chat"))
        invalidateStudioUserInstructions(store, "chat", preserveQuestionnaireContext = true)
        captureStudioUserInstructions(store, "chat", FIRST, "Continue")
        val authority = assertNotNull(store.get(ChatsKey)?.single()?.humanAuthority)
        assertClosed(authority)
        assertEquals(emptyList(), authority.messages)
    }

    @Test
    fun `repeated automatic answers preserve human context across restart but never tool authority`() = runTest {
        var store = contextStore()
        captureStudioUserInstructions(store, "chat", FIRST, "  Never read secrets.\nOnly inspect features.  ")
        val original = assertIs<StudioQuestionnaireHumanContext.Complete>(
            readStudioQuestionnaireHumanContext(store, "chat"),
        )
        repeat(3) {
            invalidateStudioUserInstructions(store, "chat", preserveQuestionnaireContext = true)
            assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
            assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
            assertEquals(
                TrustedUserInstructions.Incomplete,
                captureStudioUserInstructions(store, "chat", RequestId("automatic-$it"), null),
            )
            store = ChecklistTestStore(ChatSpec).apply { values.value = store.values.value }
        }
        assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
    }

    @Test
    fun `manual revocation after automatic answers permanently closes both states`() = runTest {
        val store = contextStore()
        captureStudioUserInstructions(store, "chat", FIRST, "Never delete files")
        invalidateStudioUserInstructions(store, "chat", preserveQuestionnaireContext = true)
        invalidateStudioUserInstructions(store, "chat")
        invalidateStudioUserInstructions(store, "chat", preserveQuestionnaireContext = true)
        captureStudioUserInstructions(store, "chat", SECOND, "Continue")
        val authority = assertNotNull(store.get(ChatsKey)?.single()?.humanAuthority)
        assertClosed(authority)
        assertEquals(listOf("Never delete files"), authority.messages.map { it.text })
    }

    @Test
    fun `genuine later human input extends only complete questionnaire context`() = runTest {
        val store = contextStore()
        captureStudioUserInstructions(store, "chat", FIRST, "Never read secrets")
        invalidateStudioUserInstructions(store, "chat", preserveQuestionnaireContext = true)
        val second = "Also avoid private files"
        assertEquals(TrustedUserInstructions.Incomplete, captureStudioUserInstructions(store, "chat", SECOND, second))
        captureStudioUserInstructions(store, "chat", SECOND, second)
        captureStudioUserInstructions(store, "chat", RequestId("generated"), null)
        val context = assertIs<StudioQuestionnaireHumanContext.Complete>(
            readStudioQuestionnaireHumanContext(store, "chat"),
        )
        assertEquals(listOf(FIRST, SECOND), context.messages.map { it.request })
        assertEquals(listOf("Never read secrets", second), context.messages.map { it.text })
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
    }

    @Test
    fun `conflicting input after automatic revocation closes context and retains original restrictions`() {
        val original = StudioHumanAuthority().append(FIRST, "Never delete files").invalidate(true)
        val conflict = original.append(FIRST, "Delete everything")
        assertClosed(conflict)
        assertEquals(original.messages, conflict.messages)
        assertEquals(conflict, conflict.append(SECOND, "Continue"))
        assertEquals(conflict, conflict.invalidate(preserveQuestionnaireContext = true))
    }

    @Test
    fun `message limit allows exact boundary then closes context without truncation`() {
        val original = StudioHumanAuthority().append(FIRST, "Never delete files").invalidate(true)
        val full = (1 until TrustedUserInstructionBounds.MAX_MESSAGES).fold(original) { authority, index ->
            authority.append(RequestId("human-$index"), "Continue")
        }
        assertIs<StudioQuestionnaireHumanContext.Complete>(full.questionnaireContextSnapshot())
        assertEquals(full, full.append(FIRST, "Never delete files"))
        assertEquals(full, full.append(SECOND, null))
        val overflow = full.append(SECOND, "Do not read secrets")
        assertClosed(overflow)
        assertEquals(full.messages, overflow.messages)
        assertEquals(overflow, overflow.append(RequestId("later"), "Continue"))
    }

    @Test
    fun `text limit allows exact boundary then closes context without losing known originals`() {
        val original = StudioHumanAuthority().append(FIRST, "Never read secrets").invalidate(true)
        val remaining = TrustedUserInstructionBounds.MAX_TEXT_CHARACTERS - original.messages.single().text.length
        val full = original.append(SECOND, "x".repeat(remaining))
        assertIs<StudioQuestionnaireHumanContext.Complete>(full.questionnaireContextSnapshot())
        val overflow = full.append(RequestId("later"), "x")
        assertClosed(overflow)
        assertEquals(full.messages, overflow.messages)
        assertClosed(overflow.invalidate(preserveQuestionnaireContext = true))
    }

    @Test
    fun `invalid stored originals fail closed for both snapshots even with explicit complete context`() {
        val message = StudioHumanInstruction(FIRST, "Never read secrets")
        val malformed = listOf(
            listOf(message, message),
            List(TrustedUserInstructionBounds.MAX_MESSAGES + 1) { StudioHumanInstruction(RequestId("$it"), "") },
            listOf(message.copy(text = "x".repeat(TrustedUserInstructionBounds.MAX_TEXT_CHARACTERS + 1))),
        )
        malformed.forEach { messages ->
            val authority = StudioHumanAuthority(messages, isComplete = true, isQuestionnaireContextComplete = true)
            assertClosed(authority)
            assertClosed(authority.append(SECOND, null))
            assertClosed(authority.invalidate(preserveQuestionnaireContext = true))
            assertEquals(messages, authority.append(SECOND, "Continue").messages)
        }
    }

    @Test
    fun `serialization preserves separation and explicit revocation through later capture`() {
        val automatic = StudioHumanAuthority().append(FIRST, "  Keep original whitespace\n").invalidate(true)
        val states = listOf(automatic, automatic.append(SECOND, "Continue"), automatic.invalidate())
        states.forEach { authority ->
            val restored = Json.decodeFromString<StudioHumanAuthority>(Json.encodeToString(authority))
            assertEquals(authority, restored)
            assertEquals(authority.questionnaireContextSnapshot(), restored.questionnaireContextSnapshot())
            assertEquals(TrustedUserInstructions.Incomplete, restored.snapshot())
        }
        val legacy = Json.decodeFromString<StudioHumanAuthority>("{\"isComplete\":false}")
        assertClosed(Json.decodeFromString<StudioHumanAuthority>(Json.encodeToString(legacy)))
    }

    @Test
    fun `automatic live barrier runs on every answer after durable authority revocation`() = runTest {
        val store = contextStore()
        captureStudioUserInstructions(store, "chat", FIRST, "Never read secrets")
        val original = readStudioQuestionnaireHumanContext(store, "chat")
        val captured = ORIGINAL.copy(nativeId = "captured")
        val revoked = mutableListOf<SessionRef>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
                assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
                revoked += session
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { null }
        repeat(2) { authority.invalidateForAutomaticAnswer("chat", captured) }
        assertEquals(listOf(captured, ORIGINAL, captured, ORIGINAL), revoked)
    }

    @Test
    fun `failed automatic durable revocation never invokes live invalidation`() = runTest {
        val store = contextStore()
        store.failWrites = true
        var revoked = false
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                revoked = true
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { null }
        assertFailsWith<IllegalStateException> { authority.invalidateForAutomaticAnswer("chat") }
        assertFalse(revoked)
        assertIs<TrustedUserInstructions.Complete>(readStudioUserInstructions(store, "chat"))
    }
}

private fun assertClosed(authority: StudioHumanAuthority) {
    assertEquals(TrustedUserInstructions.Incomplete, authority.snapshot())
    assertEquals(StudioQuestionnaireHumanContext.Incomplete, authority.questionnaireContextSnapshot())
}

private suspend fun contextStore(authority: StudioHumanAuthority? = StudioHumanAuthority()): ChecklistTestStore =
    ChecklistTestStore(ChatSpec).apply {
        set(
            ChatsKey,
            listOf(
                StudioChatRecord(
                    "chat",
                    "Chat",
                    Instant.fromEpochSeconds(0),
                    ref = ORIGINAL,
                    humanAuthority = authority,
                ),
            ),
        )
    }

private val ORIGINAL = SessionRef(EngineId("engine"), SessionSourceId("local"), "original")
private val FIRST = RequestId("first")
private val SECOND = RequestId("second")
