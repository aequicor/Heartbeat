package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioGraphStopsTest {
    @Test
    fun `legacy terminal or fresh idle cannot veto stopping a later independent graph root`() = runTest {
        for (phase in listOf(StudioLegacyPhase.Terminal, StudioLegacyPhase.Idle)) {
            val stores = ChecklistTestStores()
            val native = NativeHelperFixture(stores)
            val helper = HelperId(native.record.id)
            val old = RequestId("R")
            val current = RequestId("Q")
            stores.keyValue(ChatSpec).set(ChatsKey, listOf(native.record.copy(lastRunRequest = old)))
            native.attempts.rememberLegacy(
                helper,
                StudioLegacyObservation(
                    old,
                    native.ref,
                    native.target,
                    null,
                    phase,
                    outcome = StudioHelperTerminalOutcome.Completed.takeIf { phase == StudioLegacyPhase.Terminal },
                ),
            )
            val receipt = StudioHelperReceipt(
                current,
                StudioHelperPhase.Accepted,
                "new-work",
                native.ref,
                native.turn.id,
                policy = StudioAttemptPolicy.GraphWork(current),
            )
            stores.keyValue(HelperAttemptsSpec).set(
                helperReceiptKey(helper),
                Json.encodeToString(mapOf(current.value to receipt)),
            )
            val record = native.record.copy(lastRunRequest = current)
            stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
            val requests = mutableListOf<RequestId>()
            val known = mapOf(old.value to GraphChatAttempt(record.id), current.value to GraphChatAttempt(record.id))
            val isStopped = stopGraphAssignments(known, record, null, native.attempts) { request ->
                requests += request
                native.attempts.requestStop(helper, request)
                val terminal = StudioHelperTerminal(
                    native.ref,
                    native.turn.id,
                    StudioHelperTerminalOutcome.Cancelled,
                    "",
                )
                native.attempts.terminal(helper, request, terminal)
                HostConversationCancellation.Terminal(terminal.conversationResult(request))
            }
            assertTrue(isStopped)
            assertEquals(listOf(current), requests)
            assertTrue(checkNotNull(native.attempts.legacy(helper, old)).isStopRequested)
            assertNull(native.attempts.receipt(helper, old))
            assertEquals(phase, native.attempts.legacy(helper, old)?.phase)
            assertTrue(native.attempts.isContextIdle(helper))
        }
    }

    @Test
    fun `unknown history cannot skip stopping the current graph root`() = runTest {
        val stores = ChecklistTestStores()
        val native = NativeHelperFixture(stores)
        val current = RequestId("Q")
        val requests = mutableListOf<RequestId>()
        val known = mapOf(
            "R" to GraphChatAttempt(native.record.id),
            current.value to GraphChatAttempt(native.record.id),
        )
        val isStopped = stopGraphAssignments(known, native.record, current, native.attempts) { request ->
            requests += request
            HostConversationCancellation.NotSubmitted(request)
        }
        assertFalse(isStopped)
        assertEquals(listOf(current), requests)
    }
}
