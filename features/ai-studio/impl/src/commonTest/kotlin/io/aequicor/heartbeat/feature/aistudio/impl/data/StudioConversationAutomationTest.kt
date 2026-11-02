package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioConversationAutomationTest {
    @Test
    fun `restart does not turn unknown or work provenance into automatic conversation permission`() = runTest {
        val fixture = Fixture()
        for (policy in listOf(
            null,
            StudioAttemptPolicy.UnknownService,
            StudioAttemptPolicy.GraphWork(RequestId("root")),
        )) {
            val record = fixture.seed(policy = policy)
            assertNull(StudioConversationAutomation(StudioHelperAttempts(fixture.stores)).capture(record))
        }
        val record = fixture.seed()
        assertNotNull(StudioConversationAutomation(StudioHelperAttempts(fixture.stores)).capture(record))
    }

    @Test
    fun `failed and cancelled conversation settle lifecycle without authorizing another automatic turn`() = runTest {
        val fixture = Fixture()
        for (outcome in listOf(StudioHelperTerminalOutcome.Failed, StudioHelperTerminalOutcome.Cancelled)) {
            val record = fixture.seed(outcome = outcome)
            val automation = StudioConversationAutomation(fixture.attempts)
            assertTrue(automation.isConversation(record, fixture.source))
            assertNull(automation.capture(record))
            assertFalse(automation.isConversation(record, RequestId("different")))
        }
    }

    @Test
    fun `source stopped during preparation fences automatic native entry but permits independent human request`() =
        runTest {
            val fixture = Fixture()
            val record = fixture.seed()
            val source = assertNotNull(StudioConversationAutomation(fixture.attempts).capture(record))
            val next = RequestId("automatic")
            fixture.attempts.prepareDelivery(fixture.helper, HelperPrompt(next, "Continue"), automaticSource = source)
            fixture.stores.keyValue(ChatSpec).set(ChatsKey, listOf(record.startedRun(next)))
            assertTrue(fixture.attempts.begin(fixture.helper, next, "sender"))
            fixture.attempts.requestStop(fixture.helper, fixture.source)
            assertFailsWith<IllegalStateException> { fixture.attempts.enterNative(fixture.helper, next, "sender") }
            assertNull(fixture.attempts.receipt(fixture.helper, next)?.nativeEntryClaim)
            fixture.attempts.cancelBeforeNative(fixture.helper, next, null, "sender")
            assertTrue(
                fixture.attempts.prepareConversation(
                    fixture.helper,
                    HelperPrompt(RequestId("human"), "New task"),
                ).isNew,
            )
        }

    @Test
    fun `automatic source cannot be swapped and later record revision rejects native admission`() = runTest {
        val fixture = Fixture()
        val record = fixture.seed()
        val source = assertNotNull(StudioConversationAutomation(fixture.attempts).capture(record))
        val next = RequestId("automatic")
        fixture.attempts.prepareDelivery(fixture.helper, HelperPrompt(next, "Continue"), automaticSource = source)
        fixture.stores.keyValue(ChatSpec).set(ChatsKey, listOf(record.startedRun(RequestId("human"))))
        assertFailsWith<IllegalStateException> { fixture.attempts.begin(fixture.helper, next, "sender") }
        assertEquals(source, fixture.attempts.receipt(fixture.helper, next)?.automaticSource)
    }

    @Test
    fun `virtual continuation retains automatic ancestor Stop after restart`() = runTest {
        val fixture = Fixture()
        val record = fixture.seed()
        val source = assertNotNull(StudioConversationAutomation(fixture.attempts).capture(record))
        val original = assertNotNull(fixture.attempts.receipt(fixture.helper, fixture.source))
        val automatic = original.copy(request = RequestId("automatic"), automaticSource = source)
        val child = original.copy(request = RequestId("child"), continuationOf = automatic.request)
        fixture.stores.keyValue(HelperAttemptsSpec).set(
            helperReceiptKey(fixture.helper),
            Json.encodeToString(
                mapOf(
                    original.request.value to original,
                    automatic.request.value to automatic,
                    child.request.value to child,
                ),
            ),
        )
        fixture.attempts.requestStop(fixture.helper, fixture.source)
        val restarted = StudioHelperAttempts(fixture.stores)
        assertFailsWith<IllegalStateException> { restarted.begin(fixture.helper, child.request, "sender") }
    }

    @Test
    fun `stopped rollover reservation cannot commit local context even while its source is valid`() = runTest {
        val fixture = Fixture()
        val record = fixture.seed()
        val source = assertNotNull(StudioConversationAutomation(fixture.attempts).capture(record))
        val request = runRequest("rollover").copy(isRollover = true, automaticSource = source)
        fixture.attempts.requestStop(fixture.helper, request.request)
        var committed = false
        assertFailsWith<IllegalStateException> {
            fixture.attempts.withAutomaticCommit(request) { committed = true }
        }
        assertFalse(committed)
    }

    @Test
    fun `ancestor Stop removes automatic answer authority from the latest conversation receipt`() = runTest {
        val fixture = Fixture()
        val record = fixture.seed()
        val automation = StudioConversationAutomation(fixture.attempts)
        val source = assertNotNull(automation.capture(record))
        val original = assertNotNull(fixture.attempts.receipt(fixture.helper, fixture.source))
        val child = original.copy(request = RequestId("automatic"), automaticSource = source)
        fixture.stores.keyValue(HelperAttemptsSpec).set(
            helperReceiptKey(fixture.helper),
            Json.encodeToString(mapOf(original.request.value to original, child.request.value to child)),
        )
        val current = record.startedRun(child.request)
        fixture.stores.keyValue(ChatSpec).set(ChatsKey, listOf(current))
        val captured = assertNotNull(automation.capture(current))
        fixture.attempts.requestStop(fixture.helper, fixture.source)
        assertNull(automation.capture(current))
        assertFalse(automation.matches(current, captured))
    }

    @Test
    fun `Stop after rollover commit fences continuation even though native source stays unchanged`() = runTest {
        val fixture = Fixture()
        val record = fixture.seed()
        val automation = StudioConversationAutomation(fixture.attempts)
        val source = assertNotNull(automation.capture(record))
        val rollover = RequestId("rollover")
        fixture.attempts.withAutomaticCommit(runRequest(rollover.value).copy(automaticSource = source)) { }
        val continuationSource = source.copy(actionFences = setOf(rollover))
        val next = RequestId("continuation")
        fixture.attempts.prepareDelivery(
            fixture.helper,
            HelperPrompt(next, "Continue"),
            automaticSource = continuationSource,
        )
        fixture.stores.keyValue(ChatSpec).set(ChatsKey, listOf(record.startedRun(next)))
        assertTrue(fixture.attempts.begin(fixture.helper, next, "sender"))
        fixture.attempts.requestStop(fixture.helper, rollover)
        assertFailsWith<IllegalStateException> { fixture.attempts.enterNative(fixture.helper, next, "sender") }
    }

    @Test
    fun `mixed continuation and automatic ancestry cycle refuses authority while shared ancestor is valid`() = runTest {
        val fixture = Fixture()
        val record = fixture.seed()
        val source = assertNotNull(StudioConversationAutomation(fixture.attempts).capture(record))
        val original = assertNotNull(fixture.attempts.receipt(fixture.helper, fixture.source))
        val child = original.copy(request = RequestId("child"), continuationOf = source.request)
        val cyclic = original.copy(automaticSource = source.copy(request = child.request))
        assertFailsWith<IllegalStateException> {
            requireAutomaticAncestors(
                mapOf(original.request.value to cyclic, child.request.value to child),
                child.request,
            )
        }
        val diamond = child.copy(automaticSource = source)
        requireAutomaticAncestors(
            mapOf(original.request.value to original, child.request.value to diamond),
            child.request,
        )
    }

    private class Fixture {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        val helper = HelperId("chat")
        val source = RequestId("source")
        private val native = NativeHelperFixture()

        suspend fun seed(
            policy: StudioAttemptPolicy? = StudioAttemptPolicy.Conversation,
            outcome: StudioHelperTerminalOutcome = StudioHelperTerminalOutcome.Completed,
        ): StudioChatRecord {
            val record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, ref = native.ref).startedRun(source)
            stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
            val receipt = StudioHelperReceipt(
                source,
                StudioHelperPhase.Terminal,
                "fingerprint",
                session = native.ref,
                turn = native.turn.id,
                policy = policy,
                terminal = StudioHelperTerminal(native.ref, native.turn.id, outcome, "Done"),
                executionSnapshot = StudioExecutionSnapshot(
                    io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings,
                    native.target,
                    null,
                    null,
                    StudioHumanAuthority(),
                ),
            )
            stores.keyValue(
                HelperAttemptsSpec,
            ).set(helperReceiptKey(helper), Json.encodeToString(mapOf(source.value to receipt)))
            return resolvedStudioIdentities(listOf(record)).single()
        }
    }
}
