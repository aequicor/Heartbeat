package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.NoStudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.RunFailureKind
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioContextCarryTest {
    private val old = SessionRef(EngineId("pi"), SessionSourceId("profile"), "old")
    private var record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, pendingSeed = "seed")
    private var stored: List<SessionItem> = listOf(user("Fix the parser"))
    private var handle: ActiveSession? = null

    @Test
    fun `a native compaction leaves the brief for the next turn which consumes it with the seed`() = runTest {
        carry { listOf(user("Fix the parser")) }.brief("chat")

        assertTrue("[1] Fix the parser" in record.pendingBrief.orEmpty())
        record = record.seeded()
        assertNull(record.pendingSeed)
        assertNull(record.pendingBrief)
    }

    @Test
    fun `a brief that cannot be built leaves the compaction successful`() = runTest {
        carry { error("transcript unavailable") }.brief("chat")

        assertNull(record.pendingBrief)
        assertEquals("seed", record.pendingSeed)
    }

    @Test
    fun `a rollover freezes the transcript seeds the next session and closes the old one`() = runTest {
        record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, ref = old, pendingBrief = "brief")
        val session = Session()
        handle = session

        val outcome = carry { stored }.rollover(rollover())

        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.isFrozenSegment })
        assertNull(record.ref)
        assertEquals(listOf(old), record.previousRefs)
        assertEquals(1, record.contextSegment)
        assertTrue("Fix the parser" in record.pendingSeed.orEmpty())
        assertNull(record.pendingBrief)
        assertEquals(ActiveSessionState.Closed, session.state.value)
        assertNull(handle)
    }

    @Test
    fun `a handle that fails to close stays for a retried close and the rollover still takes effect`() = runTest {
        record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, ref = old)
        val session = Session(closeFailure = IllegalStateException("runtime busy"))
        handle = session

        val outcome = carry { stored }.rollover(rollover())

        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(listOf(old), record.previousRefs)
        assertSame(session, handle)
        assertTrue(session.state.value is ActiveSessionState.Closing)
    }

    @Test
    fun `a briefed rollover clears the failure of its handoff summary turn`() = runTest {
        val failed = StudioChatRecord(
            "chat",
            "Chat",
            Instant.DISTANT_PAST,
            ref = old,
            hasFailed = true,
            failureKind = RunFailureKind.Limit,
        )
        record = failed
        carry { stored }.rollover(rollover().copy(isBriefed = true))
        assertFalse(record.hasFailed)
        assertEquals(RunFailureKind.Unknown, record.failureKind)

        record = failed
        carry { stored }.rollover(rollover())
        assertTrue(record.hasFailed)
    }

    @Test
    fun `a chat without a native session does not roll over`() = runTest {
        assertEquals(RunOutcome.Failed, carry { stored }.rollover(rollover()))
        assertTrue(record.previousRefs.isEmpty())
    }

    @Test
    fun `outside rollover preserves the transcript and seeds the next native session`() = runTest {
        record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, ref = old)
        val session = Session()
        handle = session

        assertEquals(RunOutcome.Completed, carry { stored }.rollover(rollover(), session, setOf("restricted")))
        assertTrue(stored.all { it.isFrozenSegment })
        assertTrue("Fix the parser" in record.pendingSeed.orEmpty())
        assertEquals(listOf(old), record.previousRefs)
        assertEquals(setOf("restricted"), record.pendingHarnesses)
        assertEquals(ActiveSessionState.Closed, session.state.value)
    }

    @Test
    fun `outside rollover rechecks ownership and idle state after reading history`() = runTest {
        record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, ref = old)
        val session = Session()
        handle = session
        val before = stored
        val carry = carry {
            session.state.value = ActiveSessionState.Unavailable(EngineFailure.Unknown())
            stored
        }

        assertFailsWith<IllegalStateException> { carry.rollover(rollover(), session) }
        assertEquals(before, stored)
        assertEquals(old, record.ref)
        assertTrue(record.previousRefs.isEmpty())
        assertSame(session, handle)
    }

    @Test
    fun `globally disabled harness carry keeps its source through restart until the next enabled prompt`() = runTest {
        record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, ref = old)
        val session = Session()
        handle = session
        var isEnabled = false
        val restored = mutableListOf<Set<String>>()
        val harnesses = object : StudioHarnessAttach by NoStudioHarnessAttach {
            override suspend fun attached(session: SessionRef): Set<String>? {
                assertEquals(old, session)
                return setOf("restricted").takeIf { isEnabled }
            }
            override suspend fun restore(harnesses: Set<String>, session: SessionRef) {
                restored += harnesses
            }
        }
        val carry = carry { stored }
        carry.environment(rollover(), session, harnesses) { }
        assertEquals(old, record.pendingHarnessSource)
        assertNull(record.pendingHarnesses)
        record = Json.decodeFromString(
            StudioChatRecord.serializer(),
            Json.encodeToString(StudioChatRecord.serializer(), record),
        )
        // A later manual rollover must not discard the unresolved source either.
        record = record.rolledOver("another seed")
        val replacement = old.copy(nativeId = "replacement")
        record = record.copy(ref = replacement)
        carry.restoreHarnesses("chat", replacement, harnesses)
        assertTrue(restored.isEmpty())
        assertEquals(old, record.pendingHarnessSource)

        isEnabled = true
        assertEquals(
            mapOf("harness" to setOf("restricted")),
            carry.prepareHarnesses("chat", harnesses).withCarriedHarnesses(emptyMap()),
        )
        carry.restoreHarnesses("chat", replacement, harnesses)
        assertEquals(listOf(setOf("restricted")), restored)
        assertNull(record.pendingHarnesses)
        assertNull(record.pendingHarnessSource)
    }

    private fun carry(items: suspend (String) -> List<SessionItem>) = StudioContextCarry(
        StudioConversationLocks(Mutex()),
        record = { record },
        items = items,
        write = { _, change -> stored = change(stored) },
        update = { _, change -> record = record.change() },
        handles = object : StudioCarriedHandles {
            override suspend fun handle(id: String): ActiveSession? = handle

            override suspend fun forget(id: String, closed: ActiveSession) {
                if (handle === closed) handle = null
            }
        },
    )

    private fun rollover() = StudioTurnRequest(
        "chat",
        prompt = "",
        settings = DefaultRunSettings,
        kind = WorktreeRunKind.Coding,
        request = RequestId("compact-1"),
        isRollover = true,
    )

    private fun user(text: String) =
        SessionItem.Message(ItemInfo(ItemId("u"), 0, 0), MessageRole.User, listOf(ContentPart.Text(text)))

    /** A failed close leaves the handle closing, as the engine contract requires. */
    private inner class Session(private val closeFailure: Exception? = null) : ActiveSession {
        override val ref = old
        override val route = ExecutionRoute(
            EngineId("pi"),
            EngineBindingId("b"),
            AuthSourceId("source"),
            AuthRevision.Known("r"),
        )
        override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
        override val features = object : EngineFeatures {
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                FeatureAccess.Unsupported
        }

        override suspend fun close() {
            closeFailure?.let {
                state.value = ActiveSessionState.Closing(EngineFailure.Unknown())
                throw it
            }
            state.value = ActiveSessionState.Closed
        }
    }
}
