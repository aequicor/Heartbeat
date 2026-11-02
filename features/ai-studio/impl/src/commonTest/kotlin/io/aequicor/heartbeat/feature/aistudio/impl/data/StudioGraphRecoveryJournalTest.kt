package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StudioGraphRecoveryJournalTest {
    @Test
    fun `old terminal cache cannot hide a later accepted native head`() {
        val f = NativeHelperFixture()
        val root = GraphNativeIdentity(f.ref, RequestId("R"), TurnId("R"))
        val child = GraphNativeIdentity(f.ref, RequestId("Q"), TurnId("Q"))
        val oldResult = GraphTaskResult(GraphTaskPhase.Succeeded, "old")
        val currentResult = GraphTaskResult(GraphTaskPhase.Succeeded, "current")
        val head = StudioRecoveryWorkResolution(
            child.request,
            StudioHelperReceipt(child.request, StudioHelperPhase.Accepted, "q", session = f.ref, turn = child.turn),
            null,
            isConsistent = true,
        )
        val journal = linkedMapOf(
            "Q" to GraphChatAttempt("helper", recoveryRoot = "R", native = child),
            "R" to GraphChatAttempt("helper", oldResult, native = root),
        )
        assertNull(journal.cachedGraphResult("helper", "R", head))
        assertEquals(
            currentResult,
            (journal + ("Q" to journal.getValue("Q").copy(result = currentResult)))
                .cachedGraphResult("helper", "R", head),
        )
        assertNull(journal.cachedGraphResult("helper", "R", head.copy(receipt = null)))
    }

    @Test
    fun `terminal only cache keeps a null native turn and requires the exact head`() {
        val f = NativeHelperFixture()
        val native = GraphNativeIdentity(f.ref, RequestId("R"), null)
        val result = GraphTaskResult(GraphTaskPhase.Succeeded, "")
        val receipt = StudioHelperReceipt(
            native.request,
            StudioHelperPhase.Terminal,
            "r",
            session = f.ref,
            terminal = StudioHelperTerminal(f.ref, null, StudioHelperTerminalOutcome.Completed, ""),
        )
        val head = StudioRecoveryWorkResolution(native.request, receipt, null, isConsistent = true)
        val journal = mapOf("R" to GraphChatAttempt("helper", result, native = native))
        assertEquals(result, journal.cachedGraphResult("helper", "R", head))
        assertNull(journal.cachedGraphResult("helper", "R", head.copy(nativeRequest = RequestId("Q"))))
    }

    @Test
    fun `lost observation cannot erase the native address already retained for an execution`() {
        val f = NativeHelperFixture()
        val native = GraphNativeIdentity(f.ref, checkNotNull(f.turn.request), f.turn.id)
        val unknown = TurnOutcome.Unknown.graphResult("")
        val saved = GraphChatAttempt("helper").withCompletion(unknown, native)
        val restored = Json.decodeFromString<GraphChatAttempt>(Json.encodeToString(saved))

        assertEquals(native, restored.withCompletion(unknown, null).native)
        assertEquals(native, restored.withCompletion(unknown, native).native)
        assertFailsWith<IllegalStateException> {
            restored.withCompletion(TurnOutcome.Completed.graphResult(""), null)
        }
    }

    @Test
    fun `recovery retains original assignment settings and refuses replacement work`() {
        val f = NativeHelperFixture()
        val work = StudioGraphWorkSnapshot(
            f.ref,
            null,
            checkNotNull(f.record.target),
            "task",
            "authority",
            DefaultRunSettings,
        )
        val saved = GraphChatAttempt("helper", work = work)
        val restored = Json.decodeFromString<GraphChatAttempt>(Json.encodeToString(saved))
        val changedDefaults = work.copy(settings = work.settings.copy(modelId = "future-model"))

        assertEquals(work, retainGraphAttempt("R", restored, saved.copy(work = changedDefaults)).work)
        for (changed in listOf(work.copy(prompt = "new task"), work.copy(directive = "new authority"))) {
            assertFailsWith<IllegalStateException> {
                retainGraphAttempt("R", restored, saved.copy(work = changed))
            }
        }
    }

    @Test
    fun `replayed graph submission preserves prior causes and cannot change native ownership`() {
        val session = SessionRef(EngineId("test"), SessionSourceId("source"), "source")
        val original = RequestInitiator(session, RequestId("original"))
        val retry = RequestInitiator(session, RequestId("retry"))
        val saved = GraphChatAttempt("helper", checkpoint = "receipt", causes = setOf(original))
        val restored = Json.decodeFromString<GraphChatAttempt>(Json.encodeToString(saved))
        val incoming = GraphChatAttempt("helper", recoveryRoot = "R", causes = setOf(retry))
        val bound = retainGraphAttempt("R", restored, incoming)
        assertEquals(setOf(original), bound.causes)
        assertEquals("receipt", bound.checkpoint)
        assertFailsWith<IllegalStateException> { retainGraphAttempt("R", bound, incoming.copy(chat = "foreign")) }
        assertFailsWith<IllegalStateException> {
            retainGraphAttempt(
                "R",
                bound,
                incoming.copy(recoveryRoot = "foreign"),
            )
        }
    }

    @Test
    fun `second restart before observer journaling retains the native receipt`() {
        val persisted = Json.encodeToString(
            mapOf("native-attempt" to GraphChatAttempt("helper", checkpoint = "native-receipt")),
        )
        val restored = Json.decodeFromString<Map<String, GraphChatAttempt>>(persisted)

        assertNull(restored.recoveryAttempt("helper", "native-attempt")?.checkpoint)
    }

    @Test
    fun `second restart returns completed native result without repeating the assignment`() {
        val f = NativeHelperFixture()
        val native = GraphNativeIdentity(f.ref, checkNotNull(f.turn.request), f.turn.id)
        val result = GraphTaskResult(GraphTaskPhase.Succeeded, "Changes committed")
        val persisted = Json.encodeToString(
            mapOf("native-attempt" to GraphChatAttempt("helper", result, "native-receipt", native = native)),
        )
        val restored = Json.decodeFromString<Map<String, GraphChatAttempt>>(persisted)

        assertNull(restored.recoveryAttempt("helper", "native-attempt")?.result)
        val receipt = StudioHelperReceipt(
            native.request,
            StudioHelperPhase.Accepted,
            "work",
            session = native.session,
            turn = native.turn,
        )
        val head = StudioRecoveryWorkResolution(native.request, receipt, null, isConsistent = true)
        assertEquals(result, restored.cachedGraphResult("helper", "native-attempt", head))
    }

    @Test
    fun `metadata recovery never chooses a checkpoint by map ordering`() {
        val journal = linkedMapOf(
            "old" to GraphChatAttempt("helper", checkpoint = "old-receipt", recoveryRoot = "root"),
            "new" to GraphChatAttempt("helper", checkpoint = "new-receipt", recoveryRoot = "root"),
            "foreign" to GraphChatAttempt("another-helper", checkpoint = "foreign-receipt", recoveryRoot = "root"),
        )

        assertNull(journal.recoveryAttempt("helper", "root")?.checkpoint)
        assertNull(journal.recoveryAttempt("unknown-helper", "missing"))
        assertEquals(setOf("old", "new"), journal.recoveryLineage("helper", "root").keys)
    }

    @Test
    fun `explicit retry cannot reuse a completed earlier attempt`() {
        val journal = mapOf(
            "completed" to GraphChatAttempt("helper", GraphTaskResult(GraphTaskPhase.Succeeded, "old result")),
        )

        assertNull(journal.recoveryAttempt("helper", previousExecution = null))
        assertNull(journal.recoveryAttempt("helper", previousExecution = "retry-before-journaling"))
        assertEquals(emptySet(), journal.recoveryLineage("helper", "retry-before-journaling").keys)
    }

    @Test
    fun `journaled attempt without receipt does not resurrect older completed work`() {
        val journal = linkedMapOf(
            "completed" to GraphChatAttempt("helper", GraphTaskResult(GraphTaskPhase.Succeeded, "old result")),
            "new" to GraphChatAttempt("helper", recoveryRoot = "completed"),
        )

        assertEquals(
            GraphChatAttempt("helper", recoveryRoot = "completed"),
            journal.recoveryAttempt("helper", "completed"),
        )
    }
}
