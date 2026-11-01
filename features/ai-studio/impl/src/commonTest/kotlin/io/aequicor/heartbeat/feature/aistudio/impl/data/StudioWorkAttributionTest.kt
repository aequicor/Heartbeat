package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperWorkBinding
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioWorkAttributionTest {
    @Test
    fun `completed work never adopts or cancels a newer independent human turn`() = runTest {
        val f = HelperMetadataFixture()
        val helper = f.records.createHelper(input)
        val journal = f.attempts
        journal.prepareWork(helper, prompt, policy)
        journal.begin(helper, prompt.request, "work")
        journal.enterNative(helper, prompt.request, "work")
        journal.accepted(helper, prompt.request, native, TurnId("work-turn"))
        journal.terminal(
            helper,
            prompt.request,
            StudioHelperTerminal(native, TurnId("work-turn"), StudioHelperTerminalOutcome.Completed, "Result"),
        )
        val human = HelperPrompt(RequestId("human"), "Independent human instruction")
        journal.prepareDelivery(helper, human)
        journal.begin(helper, human.request, "human")
        journal.enterNative(helper, human.request, "human")
        journal.accepted(helper, human.request, native, TurnId("human-turn"))
        f.store.set(
            ChatsKey,
            f.store.get(ChatsKey).orEmpty().map { it.copy(ref = native, lastRunRequest = human.request) },
        )
        val metadata = f.records().helperMetadata(helper)
        assertNull(metadata?.lastRequest)
        assertTrue(metadata?.isRequestOwnershipResolved == true)
        val chats = f.chats(this)
        val unchanged = journal.receipt(helper, human.request)
        assertIs<HelperCancellation.Terminal>(chats.cancel(helper, prompt.request))
        assertIs<HelperCancellation.Unconfirmed>(chats.cancel(helper, human.request))
        assertEquals(unchanged, journal.receipt(helper, human.request))
        assertFalse(journal.receipt(helper, human.request)?.isStopRequested == true)
    }

    @Test
    fun `legacy native evidence is classified only by the exact work journal and keeps its phase`() = runTest {
        val f = HelperMetadataFixture()
        val helper = f.records.createHelper(input)
        f.attempts.prepare(helper, prompt)
        assertFalse(f.attempts.prepareDelivery(helper, prompt).isNew)
        assertNull(f.attempts.receipt(helper, prompt.request)?.policy)
        f.attempts.begin(helper, prompt.request, "legacy")
        f.attempts.accepted(helper, prompt.request, native, TurnId("old-turn"))
        f.store.set(
            ChatsKey,
            f.store.get(ChatsKey).orEmpty().map { it.copy(ref = native, lastRunRequest = prompt.request) },
        )
        val chats = f.chats(this)
        assertFalse(f.records.helperMetadata(helper)?.isRequestOwnershipResolved == true)
        val before = f.attempts.receipt(helper, prompt.request)
        assertIs<HelperCancellation.Unconfirmed>(chats.cancel(helper, prompt.request))
        assertEquals(before, f.attempts.receipt(helper, prompt.request))
        assertFalse(chats.reconcileWork(helper, prompt.request, binding.copy(owner = ActionId("foreign"))))
        assertTrue(chats.reconcileWork(helper, prompt.request, binding))
        val after = f.attempts.receipt(helper, prompt.request)
        assertEquals(before?.phase, after?.phase)
        assertEquals(before?.session, after?.session)
        assertEquals(before?.turn, after?.turn)
        assertEquals(binding, (after?.policy as? StudioAttemptPolicy.OwnedHelperWork)?.binding)
        assertFalse(chats.reconcileWork(helper, prompt.request, binding.copy(reservation = ActionId("wrong-slot"))))
        assertEquals(prompt.request, f.records().helperMetadata(helper)?.lastRequest)
    }

    @Test
    fun `conversation provenance cannot be claimed as work and work policy survives a changed seed`() = runTest {
        val journal = StudioHelperAttempts(ChecklistTestStores())
        val helper = HelperId("helper")
        journal.prepareConversation(helper, prompt)
        assertFalse(journal.reconcileWork(helper, prompt.request, policy))
        assertFailsWith<IllegalStateException> { journal.prepareWork(helper, prompt, policy) }
        val other = prompt.copy(request = RequestId("work"))
        journal.cancelBeforeSubmission(helper, prompt.request)
        journal.prepareWork(helper, other, policy)
        val repeated = journal.prepareWork(helper, other, policy.copy(model = HelperModelOptions("changed")))
        assertFalse(repeated.isNew)
        assertEquals(policy, repeated.receipt.policy)
        assertFailsWith<IllegalStateException> {
            journal.prepareWork(helper, other, policy.copy(binding = binding.copy(owner = ActionId("foreign"))))
        }
    }

    @Test
    fun `an empty target can be fenced but missing evidence of an opened target stays uncertain`() = runTest {
        val f = HelperMetadataFixture()
        val helper = f.records.createHelper(input)
        val chats = f.chats(this)
        assertTrue(chats.reconcileWork(helper, prompt.request, binding))
        assertEquals(StudioHelperPhase.NotSubmitted, f.attempts.receipt(helper, prompt.request)?.phase)
        assertFalse(f.attempts.begin(helper, prompt.request))
        val opened = f.records.createHelper(input)
        f.store.set(
            ChatsKey,
            f.store.get(ChatsKey).orEmpty().map {
                if (it.id == opened.value) it.copy(ref = native, lastRunRequest = prompt.request) else it
            },
        )
        assertTrue(chats.reconcileWork(opened, prompt.request, binding))
        val uncertain = f.attempts.requestStop(opened, prompt.request)
        assertEquals(StudioHelperPhase.Submitting, uncertain.phase)
        assertFalse(uncertain.isNativeEntryTracked)
        assertTrue(uncertain.isStopRequested)
    }
}

private val binding = HelperWorkBinding(ActionId("work-owner"), ActionId("reservation"))
private val policy = StudioAttemptPolicy.OwnedHelperWork(binding, HelperModelOptions("initial"))
private val prompt = HelperPrompt(RequestId("work-request"), "Work", work = binding)
private val native = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
private val input = HelperCreateRequest(binding.owner, null, null, null, "Helper", TrustLevel.Ask)

private object NoWorkNative : StudioHelperRuns {
    override suspend fun runHelper(
        helper: HelperId,
        prompt: HelperPrompt,
        submission: StudioHelperSubmission,
        onAccepted: suspend (SessionRef, TurnId) -> Unit,
    ): Unit = error("No native execution is allowed")
    override suspend fun helperTerminal(helper: HelperId, request: RequestId): StudioHelperTerminal? =
        error("No native observation is needed")
    override suspend fun stopHelper(helper: HelperId, request: RequestId): StudioHelperTerminal? =
        error("No unrelated turn may be stopped")
}

private fun HelperMetadataFixture.chats(scope: CoroutineScope) = EngineStudioHelperChats(
    records(),
    attempts,
    emptyHelperAdmissions(),
    lazyOf(NoWorkNative),
    RunProfile(scope),
)
