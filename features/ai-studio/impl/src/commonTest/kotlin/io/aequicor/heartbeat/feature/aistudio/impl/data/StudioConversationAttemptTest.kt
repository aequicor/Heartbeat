package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioConversationAttemptTest {
    private val helper = HelperId("helper")
    private val prompt = HelperPrompt(RequestId("R"), "private prompt")
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val terminal = StudioHelperTerminal(
        session,
        TurnId("turn"),
        StudioHelperTerminalOutcome.Completed,
        "private answer",
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `stop during policy preparation revokes the actual sender and survives restart`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        val gate = StudioHelperSubmission(journal, emptyHelperAdmissions(), helper, prompt.request)
        val preparing = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        var nativeCalls = 0
        val sender = async {
            gate.withPreparation(session, null) {
                gate.begin {
                    preparing.complete(Unit)
                    held.await()
                }
                gate.send { nativeCalls++ }
            }
        }
        preparing.await()
        assertEquals(StudioHelperPhase.NotSubmitted, journal.requestStop(helper, prompt.request).phase)
        runCurrent()
        assertFailsWith<CancellationException> { sender.await() }
        assertEquals(0, nativeCalls)
        val restored = StudioHelperAttempts(stores)
        assertTrue(restored.receipt(helper, prompt.request)?.isFinal == true)
        assertFalse(restored.prepareDelivery(helper, prompt).isNew)
        assertFalse(restored.begin(helper, prompt.request, "late"))
    }

    @Test
    fun `entered native work and legacy submissions stay uncertain until exact terminal evidence`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        journal.bindSession(helper, prompt.request, session)
        journal.begin(helper, prompt.request, "sender")
        assertTrue(journal.enterNative(helper, prompt.request, "sender"))
        val stopping = journal.requestStop(helper, prompt.request)
        assertEquals(StudioHelperPhase.Submitting, stopping.phase)
        assertTrue(stopping.isStopRequested)
        assertFalse(journal.enterNative(helper, prompt.request, "sender"))
        assertFailsWith<IllegalStateException> { journal.prepare(helper, prompt.copy(request = RequestId("new"))) }
        journal.accepted(helper, prompt.request, session, terminal.turn)
        journal.terminal(helper, prompt.request, terminal.copy(outcome = StudioHelperTerminalOutcome.Cancelled))
        val restored = StudioHelperAttempts(stores)
        assertTrue(restored.prepare(helper, prompt.copy(request = RequestId("new"))).isNew)
        val untouched = restored.receipt(helper, RequestId("new"))
        assertEquals(StudioHelperPhase.Terminal, restored.requestStop(helper, prompt.request).phase)
        assertEquals(untouched, restored.receipt(helper, RequestId("new")))

        val raw = stores.stores.getValue(HelperAttemptsSpec).values
        raw.value = raw.value.mapValues { (_, value) ->
            val old = Json.decodeFromString<Map<String, StudioHelperReceipt>>(value as String)
            Json.encodeToString(
                old.mapValues { (_, receipt) ->
                    if (receipt.request == RequestId("new")) {
                        receipt.copy(
                            phase = StudioHelperPhase.Submitting,
                            isNativeEntryTracked = false,
                            submissionClaim = "legacy",
                        )
                    } else {
                        receipt
                    }
                },
            )
        }
        assertEquals(
            StudioHelperPhase.Submitting,
            StudioHelperAttempts(stores).requestStop(helper, RequestId("new")).phase,
        )
    }

    @Test
    fun `source evidence is immutable and cannot be attached after submission`() = runTest {
        val journal = StudioHelperAttempts(ChecklistTestStores())
        journal.prepare(helper, prompt)
        val cause = setOf(RequestInitiator(session, RequestId("original")))
        assertEquals(cause, journal.bindCauses(helper, prompt.request, cause).causes)
        assertEquals(cause, journal.bindCauses(helper, prompt.request, cause).causes)
        assertFailsWith<IllegalStateException> {
            journal.bindCauses(helper, prompt.request, setOf(RequestInitiator(session, RequestId("forged"))))
        }
        journal.begin(helper, prompt.request, "sender")
        assertFailsWith<IllegalStateException> { journal.bindCauses(helper, prompt.request, cause) }
    }

    @Test
    fun `a missing journal of a recorded run cannot authorize another native attempt`() = runTest {
        val stores = ChecklistTestStores()
        val journal = StudioHelperAttempts(stores)
        journal.prepare(helper, prompt)
        val opened = StudioChatRecord(
            helper.value,
            "Helper",
            kotlin.time.Instant.fromEpochSeconds(10),
            ref = session,
            lastRunRequest = prompt.request,
            helper = StudioHelperIdentity(
                io.aequicor.heartbeat.feature.scheduler.api.ActionId("work"),
                null,
                null,
                io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel.Ask,
            ),
        )
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(opened))
        stores.stores.getValue(HelperAttemptsSpec).values.value = emptyMap()
        assertFailsWith<IllegalStateException> { journal.prepare(helper, prompt.copy(request = RequestId("new"))) }
        assertFailsWith<IllegalStateException> { journal.requestStop(helper, prompt.request) }
    }
}
