package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperWorkBinding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioTurnOriginTest {
    private val helper = HelperId("helper")
    private val record = StudioChatRecord(
        helper.value,
        "Chat",
        Instant.DISTANT_PAST,
        helper = StudioHelperIdentity(ActionId("old-work"), null, null, TrustLevel.Ask),
    )

    @Test
    fun `admission preserves explicit graph and unknown service origin instead of conversation fallback`() = runTest {
        val root = RequestId("graph-root")
        val origins = listOf(
            StudioTurnOrigin.Conversation to StudioAttemptPolicy.Conversation,
            StudioTurnOrigin.GraphWork(root) to StudioAttemptPolicy.GraphWork(root),
            StudioTurnOrigin.UnknownService to StudioAttemptPolicy.UnknownService,
        )
        for ((origin, expected) in origins) {
            val stores = ChecklistTestStores()
            val attempts = StudioHelperAttempts(stores)
            val request = runRequest("request").copy(id = record.id, origin = origin)
            val admitted = StudioHelperAdmission(attempts, emptyHelperAdmissions()).prepare(record, request)
            assertNotNull(admitted.submission)
            val saved = assertNotNull(attempts.receipt(helper, request.request))
            assertEquals(expected, saved.policy)
            val restarted = StudioHelperAttempts(stores)
            assertEquals(saved, restarted.receipt(helper, request.request))
            val reconciled = restarted.reconcileWork(
                helper,
                request.request,
                StudioAttemptPolicy.OwnedHelperWork(HelperWorkBinding(ActionId("old-work")), HelperModelOptions()),
            )
            assertFalse(reconciled)
            val work = restarted.workOwnership(helper, ActionId("old-work"), request.request)
            assertNull(work.request)
            assertEquals(expected != StudioAttemptPolicy.UnknownService, work.isResolved)
            assertEquals(saved, restarted.receipt(helper, request.request))
        }
    }

    @Test
    fun `declaring helper work never substitutes for its authenticated gate even in an ordinary root`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val request = runRequest("work").copy(id = record.id, origin = StudioTurnOrigin.HelperWork)
        assertFailsWith<IllegalStateException> {
            StudioHelperAdmission(attempts, emptyHelperAdmissions()).prepare(record.copy(helper = null), request)
        }
        assertNull(attempts.receipt(helper, request.request))
    }

    @Test
    fun `an explicit origin cannot adopt a gate with a different policy even in an ordinary root`() = runTest {
        val expected = RequestId("expected")
        val different = RequestId("different")
        val cases = listOf(
            StudioTurnOrigin.HelperWork to StudioAttemptPolicy.Conversation,
            StudioTurnOrigin.Conversation to StudioAttemptPolicy.UnknownService,
            StudioTurnOrigin.GraphWork(expected) to StudioAttemptPolicy.GraphWork(different),
        )
        for ((origin, policy) in cases) {
            val attempts = StudioHelperAttempts(ChecklistTestStores())
            val request = runRequest("mismatch").copy(id = record.id, origin = origin)
            val prompt = HelperPrompt(request.request, request.prompt)
            val prepared = attempts.prepareDelivery(helper, prompt, policy).receipt
            val gate = StudioHelperSubmission(attempts, emptyHelperAdmissions(), helper, request.request)
            assertFailsWith<IllegalStateException> {
                StudioHelperAdmission(attempts, emptyHelperAdmissions()).prepare(
                    record.copy(helper = null),
                    request.copy(submission = gate),
                )
            }
            assertEquals(prepared, attempts.receipt(helper, request.request))
        }
    }

    @Test
    fun `new service policies round trip and never match a different graph or work lease`() {
        val graph = StudioAttemptPolicy.GraphWork(RequestId("root"))
        val encoded = Json.encodeToString<StudioAttemptPolicy>(graph)
        assertEquals(graph, Json.decodeFromString<StudioAttemptPolicy>(encoded))
        assertFalse(graph.hasSameOrigin(StudioAttemptPolicy.GraphWork(RequestId("other"))))
        assertFalse(StudioAttemptPolicy.UnknownService.hasSameOrigin(StudioAttemptPolicy.Conversation))
    }
}
