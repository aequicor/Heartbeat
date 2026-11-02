package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioRecoveryWitnessTest {
    private val helper = HelperId("chat")
    private val execution = RequestId("Q")
    private val source = RequestId("R")
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val turn = TurnId("native-turn")
    private val initial = StudioRecoveryWitness(execution, source, setOf(source), session)

    @Test
    fun `restart transfers one observer while preserving exact native binding and stop`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        attempts.prepareRecovery(helper, initial)
        attempts.observeRecovery(helper, execution, session, source, turn)
        attempts.stopRecovery(helper, execution)
        attempts.loseRecovery(helper, execution)
        val successor = RequestId("successor")
        val restored = StudioHelperAttempts(stores)
        val next = initial.copy(execution = successor)

        val transferred = restored.prepareRecovery(helper, next)

        assertEquals(source, transferred.observed)
        assertEquals(turn, transferred.turn)
        assertTrue(transferred.isStopRequested)
        assertEquals(StudioRecoveryPhase.Unknown, transferred.phase)
        assertEquals(successor, restored.recovery(helper, execution)?.observerSuccessor)
        assertEquals(transferred, StudioHelperAttempts(stores).prepareRecovery(helper, next))
        assertEquals(listOf(successor), restored.stopRecoveryTargets(helper, source).map { it.execution })
        assertFalse(restored.isContextIdle(helper))
        assertFailsWith<CancellationException> { restored.observeRecovery(helper, execution, session, source, turn) }
        assertFailsWith<CancellationException> { restored.loseRecovery(helper, execution) }
        assertFailsWith<CancellationException> { restored.submitRecovery(helper, execution) }
        assertFailsWith<CancellationException> {
            restored.prepareConversation(helper, HelperPrompt(execution, "stale send"))
        }
        assertFailsWith<CancellationException> { restored.begin(helper, execution) }
        assertFailsWith<CancellationException> { restored.enterNative(helper, execution, "stale") }
        assertFailsWith<CancellationException> { restored.bindSession(helper, execution, session) }
        assertFailsWith<CancellationException> { restored.cancelBeforeNative(helper, execution, session, "stale") }
        assertNull(restored.receipt(helper, execution))
        assertNull(restored.receipt(helper, successor))
    }

    @Test
    fun `stop of any alias reaches a single owner through multiple transfers`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.observeRecovery(helper, execution, session, source, turn)
        val middle = RequestId("middle")
        val latest = RequestId("latest")
        attempts.prepareRecovery(helper, initial.copy(execution = middle))
        attempts.prepareRecovery(helper, initial.copy(execution = latest))

        assertEquals(listOf(latest), attempts.stopRecoveryTargets(helper, execution).map { it.execution })

        for (request in listOf(execution, middle, latest)) {
            assertTrue(checkNotNull(attempts.recovery(helper, request)).isStopRequested)
        }
        attempts.observeRecovery(helper, latest, session, source, turn, StudioHelperTerminalOutcome.Cancelled)
        assertTrue(attempts.isContextIdle(helper))
        assertFailsWith<CancellationException> { attempts.submitRecovery(helper, latest) }
    }

    @Test
    fun `an uncertain new send never transfers the older cancelled observation`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.observeRecovery(helper, execution, session, source, turn, StudioHelperTerminalOutcome.Cancelled)
        attempts.submitRecovery(helper, execution)

        assertFailsWith<IllegalStateException> {
            attempts.prepareRecovery(helper, initial.copy(execution = RequestId("successor")))
        }
        assertNull(attempts.recovery(helper, RequestId("successor")))
        assertNull(attempts.recovery(helper, execution)?.observerSuccessor)
    }

    @Test
    fun `terminal cancellation does not clear an explicit stop for the same work root`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.observeRecovery(helper, execution, session, source, turn)
        attempts.stopRecovery(helper, execution)
        attempts.observeRecovery(helper, execution, session, source, turn, StudioHelperTerminalOutcome.Cancelled)
        val next = RequestId("next")

        assertTrue(attempts.prepareRecovery(helper, initial.copy(execution = next)).isStopRequested)
        assertFailsWith<CancellationException> { attempts.submitRecovery(helper, next) }
        assertNull(attempts.receipt(helper, next))
    }

    @Test
    fun `failed transfer write leaves the previous observer as the only owner`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        attempts.prepareRecovery(helper, initial)
        val next = initial.copy(execution = RequestId("next"))
        stores.keyValue(HelperAttemptsSpec).failWrites = true

        assertFailsWith<IllegalStateException> { attempts.prepareRecovery(helper, next) }

        stores.keyValue(HelperAttemptsSpec).failWrites = false
        assertNull(attempts.recovery(helper, execution)?.observerSuccessor)
        assertNull(attempts.recovery(helper, next.execution))
        assertEquals(next, attempts.prepareRecovery(helper, next))
        assertEquals(next, StudioHelperAttempts(stores).prepareRecovery(helper, next))
    }

    @Test
    fun `late stop of a terminal predecessor fences an already prepared successor send`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.observeRecovery(helper, execution, session, source, turn, StudioHelperTerminalOutcome.Cancelled)
        val next = RequestId("next")
        attempts.prepareRecovery(helper, initial.copy(execution = next))
        attempts.submitRecovery(helper, next)
        attempts.prepareConversation(helper, HelperPrompt(next, "continuation"))

        attempts.stopRecovery(helper, execution)

        assertFailsWith<CancellationException> { attempts.begin(helper, next) }
        assertFailsWith<CancellationException> { attempts.enterNative(helper, next, "late") }
        assertEquals(StudioHelperPhase.Preparing, attempts.receipt(helper, next)?.phase)
    }

    @Test
    fun `a new independent work root does not inherit a stopped terminal predecessor`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.stopRecovery(helper, execution)
        attempts.observeRecovery(helper, execution, session, source, turn, StudioHelperTerminalOutcome.Cancelled)
        val independent = RequestId("independent")
        val next = RequestId("next")

        val reserved = attempts.prepareRecovery(
            helper,
            StudioRecoveryWitness(next, independent, setOf(independent), session),
        )

        assertFalse(reserved.isStopRequested)
        attempts.submitRecovery(helper, next)
        attempts.prepareDelivery(helper, HelperPrompt(next, "new work"), StudioAttemptPolicy.GraphWork(independent))
        assertTrue(attempts.begin(helper, next, "new-claim"))
        assertTrue(attempts.enterNative(helper, next, "new-claim"))
    }

    @Test
    fun `ownership transfer cannot change root session or remove candidates`() = runTest {
        for (next in listOf(
            initial.copy(execution = RequestId("next"), session = session.copy(nativeId = "other")),
            initial.copy(
                execution = RequestId("next"),
                root = RequestId("different"),
                candidates = setOf(RequestId("different")),
            ),
        )) {
            val attempts = StudioHelperAttempts(ChecklistTestStores())
            attempts.prepareRecovery(helper, initial)
            assertFailsWith<IllegalStateException> { attempts.prepareRecovery(helper, next) }
            assertNull(attempts.recovery(helper, execution)?.observerSuccessor)
            assertNull(attempts.recovery(helper, next.execution))
        }
    }

    @Test
    fun `broken or cyclic ownership cannot discard the original witness`() {
        val next = RequestId("next")
        val transferred = initial.copy(observerSuccessor = next)
        assertFailsWith<IllegalStateException> { validateRecoveryLineage(mapOf(execution.value to transferred)) }
        assertFailsWith<IllegalStateException> {
            validateRecoveryLineage(
                mapOf(
                    execution.value to transferred,
                    next.value to initial.copy(execution = next, observerSuccessor = execution),
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            validateRecoveryLineage(
                mapOf(execution.value to transferred.stopped(), next.value to initial.copy(execution = next)),
            )
        }
    }

    @Test
    fun `recovery cannot bind a stale native reference captured before replacement`() = runTest {
        val stores = ChecklistTestStores()
        val routes = StudioHelperRoutes()
        val attempts = StudioHelperAttempts(stores, routes)
        val commit = CompletableDeferred<Unit>()
        val replacement = async {
            routes.withLock(helper) {
                commit.await()
                stores.keyValue(ChatSpec).set(
                    ChatsKey,
                    listOf(
                        StudioChatRecord(
                            helper.value,
                            "Chat",
                            Instant.fromEpochMilliseconds(0),
                            ref = session.copy(nativeId = "replacement"),
                        ),
                    ),
                )
            }
        }
        runCurrent()
        val recovery = async {
            assertFailsWith<IllegalStateException> { attempts.prepareRecovery(helper, initial) }
        }
        runCurrent()
        assertFalse(recovery.isCompleted)
        commit.complete(Unit)
        replacement.await()
        recovery.await()
        assertNull(attempts.recovery(helper, execution))
    }

    @Test
    fun `legacy missing send history cannot prevent durable recovery stop intent`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        stores.keyValue(ChatSpec).set(
            ChatsKey,
            listOf(
                StudioChatRecord(
                    helper.value,
                    "Chat",
                    Instant.fromEpochMilliseconds(0),
                    ref = session,
                    helper = StudioHelperIdentity(ActionId("owner"), null, null, TrustLevel.Ask),
                    lastRunRequest = source,
                ),
            ),
        )
        attempts.prepareRecovery(helper, initial)
        assertFailsWith<EngineException> { attempts.requestStop(helper, execution) }
        assertTrue(checkNotNull(attempts.recovery(helper, execution)).isStopRequested)
        assertNull(attempts.receipt(helper, execution))
    }

    @Test
    fun `generic preparation cleanup cannot create a recovery tombstone or imply an explicit stop`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.observeRecovery(helper, execution, session, source, turn)
        assertFailsWith<EngineException> { attempts.cancelBeforeSubmission(helper, execution, isFinal = false) }
        assertNull(attempts.receipt(helper, execution))
        assertFalse(checkNotNull(attempts.recovery(helper, execution)).isStopRequested)
        assertFailsWith<EngineException> { attempts.cancelBeforeSubmission(helper, execution) }
        assertNull(attempts.receipt(helper, execution))
        assertTrue(checkNotNull(attempts.recovery(helper, execution)).isStopRequested)
    }

    @Test
    fun `generic stop cannot mistake a recovery witness for a request never submitted`() = runTest {
        for (submit in listOf(false, true)) {
            val attempts = StudioHelperAttempts(ChecklistTestStores())
            attempts.prepareRecovery(helper, initial)
            if (submit) attempts.submitRecovery(helper, execution)
            assertFailsWith<EngineException> { attempts.requestStop(helper, execution) }
            assertTrue(checkNotNull(attempts.recovery(helper, execution)).isStopRequested)
            assertNull(attempts.receipt(helper, execution))
            assertFailsWith<CancellationException> {
                attempts.prepareConversation(helper, HelperPrompt(execution, "late prompt"))
            }
        }
    }

    @Test
    fun `an unresolved observation also bars an independent new send`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        assertFailsWith<IllegalStateException> {
            attempts.prepareConversation(helper, HelperPrompt(RequestId("another"), "new prompt"))
        }
    }

    @Test
    fun `observing the same execution request never permits replay of its accepted send`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val prompt = HelperPrompt(source, "original prompt")
        attempts.prepareConversation(helper, prompt)
        attempts.begin(helper, source)
        attempts.accepted(helper, source, session, turn)
        attempts.prepareRecovery(helper, initial.copy(execution = source))
        val observed = attempts.observeRecovery(helper, source, session, source, turn)
        assertEquals(source, observed.observed)
        attempts.observeRecovery(helper, source, session, source, turn, StudioHelperTerminalOutcome.Cancelled)
        attempts.terminal(
            helper,
            source,
            StudioHelperTerminal(session, turn, StudioHelperTerminalOutcome.Cancelled, ""),
        )
        assertFailsWith<IllegalStateException> { attempts.submitRecovery(helper, source) }
        assertFalse(attempts.prepareDelivery(helper, prompt).isNew)
    }

    @Test
    fun `recovery waits for context replacement commit then bars the next replacement`() = runTest {
        val routes = StudioHelperRoutes()
        val attempts = StudioHelperAttempts(ChecklistTestStores(), routes)
        val commit = CompletableDeferred<Unit>()
        val replacement = async {
            routes.withLock(helper) {
                assertTrue(attempts.isContextIdle(helper))
                commit.await()
            }
        }
        runCurrent()
        val recovery = async { attempts.prepareRecovery(helper, initial) }
        runCurrent()
        assertFalse(recovery.isCompleted)
        commit.complete(Unit)
        replacement.await()
        assertEquals(initial, recovery.await())
        routes.withLock(helper) { assertFalse(attempts.isContextIdle(helper)) }
    }

    @Test
    fun `stop before binding survives restart and never creates a send receipt`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        attempts.prepareRecovery(helper, initial)
        attempts.stopRecovery(helper, execution)
        val restored = StudioHelperAttempts(stores)
        val bound = restored.observeRecovery(helper, execution, session, source, turn)
        assertTrue(bound.isStopRequested)
        assertEquals(StudioRecoveryPhase.Observing, bound.phase)
        assertEquals(source, bound.observed)
        assertNull(restored.receipt(helper, source))
        assertNull(restored.receipt(helper, execution))
        assertFailsWith<CancellationException> { restored.submitRecovery(helper, execution) }
        assertFalse(restored.isContextIdle(helper))
    }

    @Test
    fun `stop forbids a fresh send even before any native identity is known`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.stopRecovery(helper, execution)
        assertFailsWith<CancellationException> { attempts.submitRecovery(helper, execution) }
        assertEquals(StudioRecoveryPhase.Inspecting, attempts.recovery(helper, execution)?.phase)
    }

    @Test
    fun `terminal only evidence permits no invented turn and may later enrich the same outcome`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        val completed = attempts.observeRecovery(
            helper,
            execution,
            session,
            source,
            null,
            StudioHelperTerminalOutcome.Completed,
        )
        assertNull(completed.turn)
        assertTrue(attempts.isContextIdle(helper))
        val enriched = attempts.observeRecovery(helper, execution, session, source, turn)
        assertEquals(StudioHelperTerminalOutcome.Completed, enriched.outcome)
        assertEquals(StudioRecoveryPhase.Terminal, enriched.phase)
        assertEquals(turn, enriched.turn)
        assertEquals(enriched, attempts.loseRecovery(helper, execution))
        assertFailsWith<IllegalStateException> {
            attempts.observeRecovery(helper, execution, session, source, turn, StudioHelperTerminalOutcome.Cancelled)
        }
        assertNull(attempts.receipt(helper, execution))
        assertNull(attempts.receipt(helper, source))
    }

    @Test
    fun `unknown observation retains binding and stop and blocks context replacement`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.observeRecovery(helper, execution, session, source, turn)
        attempts.stopRecovery(helper, execution)
        val unknown = attempts.loseRecovery(helper, execution)
        assertEquals(source, unknown.observed)
        assertEquals(turn, unknown.turn)
        assertTrue(unknown.isStopRequested)
        assertFalse(attempts.isContextIdle(helper))
    }

    @Test
    fun `a submit decision needs its own send receipt before context carry is allowed`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        attempts.submitRecovery(helper, execution)
        assertFalse(attempts.isContextIdle(helper))
        attempts.prepareConversation(helper, HelperPrompt(execution, "new prompt"))
        assertFalse(attempts.isContextIdle(helper))
        val own = StudioHelperSubmission(attempts, emptyHelperAdmissions(), helper, execution)
        assertTrue(attempts.isContextIdle(helper, own))
        attempts.cancelBeforeSubmission(helper, execution)
        assertTrue(attempts.isContextIdle(helper))
    }

    @Test
    fun `replay cannot change recovery lineage context or clear stop intent`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareRecovery(helper, initial)
        val stopped = attempts.stopRecovery(helper, execution)
        assertEquals(stopped, attempts.prepareRecovery(helper, initial))
        assertFailsWith<IllegalStateException> {
            attempts.prepareRecovery(helper, initial.copy(session = session.copy(nativeId = "another")))
        }
        assertFailsWith<IllegalStateException> {
            attempts.observeRecovery(helper, execution, session, RequestId("unrelated"), turn)
        }
        assertFailsWith<IllegalStateException> {
            attempts.stopRecovery(helper, RequestId("missing"))
        }
        assertNull(attempts.receipt(helper, RequestId("missing")))
    }

    @Test
    fun `only a cancelled native observation can become a new send decision`() {
        val cancelled = initial.observed(source, session, null, StudioHelperTerminalOutcome.Cancelled)
        val submit = cancelled.submitting()
        assertEquals(StudioRecoveryPhase.Submit, submit.phase)
        assertEquals(source, submit.observed)
        assertNull(submit.turn)
        assertFailsWith<IllegalStateException> {
            initial.observed(source, session, turn).submitting()
        }
        assertFailsWith<IllegalStateException> {
            initial.observed(source, session, null, StudioHelperTerminalOutcome.Completed).submitting()
        }
    }
}
