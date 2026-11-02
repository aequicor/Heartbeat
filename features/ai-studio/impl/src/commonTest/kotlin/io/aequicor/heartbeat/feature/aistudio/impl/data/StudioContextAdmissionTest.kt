package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperWorkBinding
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioContextAdmissionTest {
    private val helper = HelperId("child")
    private val prompt = HelperPrompt(RequestId("message"), "Hello")
    private val native = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val record = StudioChatRecord(
        helper.value,
        "Child",
        Instant.fromEpochSeconds(0),
        identity = StudioSessionIdentity(
            StudioIdentityOrigin.AgentCreated,
            LogicalSessionContext(
                LogicalSessionId("root"),
                SessionKind.Session,
            ).subsession(LogicalSessionId(helper.value)),
        ),
    )

    @Test
    fun `a full subsession without a helper marker still excludes unresolved and missing native evidence`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        val changes = StudioEngineSwitchHelpers(attempts, StudioHelperRoutes(), UnusedFacade, stores)
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record.copy(lastRunRequest = prompt.request, ref = native)))
        assertFailsWith<StudioEngineSwitchRefused> { changes.withIdleSource(record) { error("must not detach") } }
        // Opening an idle handle for configuration alone does not claim an earlier turn existed.
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record.copy(ref = native)))
        changes.withIdleSource(record) { }
        attempts.prepareConversation(helper, prompt)
        assertFailsWith<StudioEngineSwitchRefused> { changes.withIdleSource(record) { error("must not detach") } }
        attempts.requestStop(helper, prompt.request)
        changes.withIdleSource(record) { }
    }

    @Test
    fun `new root writer cannot treat missing admission history as legacy or fabricate a stop tombstone`() = runTest {
        val stores = ChecklistTestStores()
        val root = record.copy(
            identity = null,
            hasAttemptJournal = true,
            lastRunRequest = prompt.request,
            ref = native,
        )
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(root))
        val attempts = StudioHelperAttempts(stores)

        assertFalse(attempts.isContextIdle(helper))
        assertFailsWith<IllegalStateException> { attempts.requestStop(helper, prompt.request) }
        assertFailsWith<IllegalStateException> {
            attempts.prepareConversation(helper, HelperPrompt(RequestId("next"), "another message"))
        }
        assertNull(attempts.receipt(helper, prompt.request))
    }

    @Test
    fun `only the exact reserved unbound conversation preparation travels with its own context`() = runTest {
        val stores = ChecklistTestStores()
        val routes = StudioHelperRoutes()
        val attempts = StudioHelperAttempts(stores, routes)
        val changes = StudioEngineSwitchHelpers(attempts, routes, UnusedFacade, stores)
        attempts.prepareConversation(helper, prompt)
        val gate = StudioHelperSubmission(attempts, emptyHelperAdmissions(), helper, prompt.request)
        val request = StudioTurnRequest(
            helper.value,
            "Hello",
            DefaultRunSettings,
            WorktreeRunKind.Coding,
            prompt.request,
            submission = gate,
        )
        assertFailsWith<IllegalStateException> {
            changes.withPreparingSource(record, request, RequestId("other")) { error("must not detach") }
        }
        changes.withPreparingSource(record, request, prompt.request) { }
        assertNotNull(attempts.bindSession(helper, prompt.request, native))
        assertFailsWith<StudioEngineSwitchRefused> {
            changes.withPreparingSource(record, request, prompt.request) { error("must not detach after binding") }
        }
    }

    @Test
    fun `old work preparations and revoked conversation gates never authorize context carry`() = runTest {
        for (isWork in listOf(false, true)) {
            val stores = ChecklistTestStores()
            val attempts = StudioHelperAttempts(stores)
            val changes = StudioEngineSwitchHelpers(attempts, StudioHelperRoutes(), UnusedFacade, stores)
            if (isWork) {
                attempts.prepareWork(
                    helper,
                    prompt,
                    StudioAttemptPolicy.OwnedHelperWork(HelperWorkBinding(ActionId("owner")), HelperModelOptions()),
                )
            } else {
                attempts.prepareConversation(helper, prompt)
                attempts.requestStop(helper, prompt.request)
            }
            val gate = StudioHelperSubmission(attempts, emptyHelperAdmissions(), helper, prompt.request)
            val request = StudioTurnRequest(
                helper.value,
                "Hello",
                DefaultRunSettings,
                WorktreeRunKind.Coding,
                prompt.request,
                submission = gate,
            )
            assertFailsWith<StudioEngineSwitchRefused> {
                changes.withPreparingSource(record, request, prompt.request) { error("must not detach") }
            }
        }
    }

    @Test
    fun `context replacement holds the same route lock until commit before accepting another request`() = runTest {
        val stores = ChecklistTestStores()
        val routes = StudioHelperRoutes()
        val attempts = StudioHelperAttempts(stores, routes)
        val changes = StudioEngineSwitchHelpers(attempts, routes, UnusedFacade, stores)
        val commit = CompletableDeferred<Unit>()
        val replacement = async { changes.withIdleSource(record) { commit.await() } }
        runCurrent()
        val delivery = async { attempts.prepareConversation(helper, prompt) }
        runCurrent()
        assertFalse(delivery.isCompleted)
        commit.complete(Unit)
        replacement.await()
        assertEquals(prompt.request, delivery.await().receipt.request)
    }

    @Test
    fun `recovered unknown work cannot appear during replacement and then excludes another replacement`() = runTest {
        val stores = ChecklistTestStores()
        val routes = StudioHelperRoutes()
        val attempts = StudioHelperAttempts(stores, routes)
        val changes = StudioEngineSwitchHelpers(attempts, routes, UnusedFacade, stores)
        val commit = CompletableDeferred<Unit>()
        val replacement = async { changes.withIdleSource(record) { commit.await() } }
        runCurrent()
        val recovery = async {
            attempts.reconcileWork(
                helper,
                prompt.request,
                StudioAttemptPolicy.OwnedHelperWork(HelperWorkBinding(ActionId("owner")), HelperModelOptions()),
            )
        }
        runCurrent()
        assertFalse(recovery.isCompleted)
        commit.complete(Unit)
        replacement.await()
        assertEquals(true, recovery.await())
        assertEquals(StudioHelperPhase.Submitting, attempts.receipt(helper, prompt.request)?.phase)
        assertFailsWith<StudioEngineSwitchRefused> {
            changes.withIdleSource(record) { error("must not detach after uncertain work recovery") }
        }
    }
}

private object UnusedFacade : EngineFacade {
    override val engines: EngineCatalog get() = error("Context admission never contacts an engine")
    override val bindings: EngineBindings get() = error("unused")
    override val models: ModelCatalog get() = error("unused")
    override val sessions: SessionCatalog get() = error("unused")
    override val providerUsage: ProviderUsageCatalog get() = error("unused")
}
