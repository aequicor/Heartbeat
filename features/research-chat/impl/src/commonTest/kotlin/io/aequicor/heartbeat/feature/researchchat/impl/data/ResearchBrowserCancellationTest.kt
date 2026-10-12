package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.browser.api.BrowserAgentControl
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResearchAccess
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchRecoveryAction
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResearchBrowserCancellationTest {
    @Test
    fun `profile closure cancels import, rejects the late attachment result and settles the live caller`() = runTest {
        val profileJob = Job(backgroundScope.coroutineContext[Job])
        val attachments = PendingAttachments()
        val fixture = Fixture(CoroutineScope(backgroundScope.coroutineContext + profileJob), attachments)
        fixture.prepare()
        val operation = async { fixture.recover("source", ResearchRecoveryAction.Continue) }
        runCurrent()
        complete(fixture, fixture.machine.requests.single(), documentResult())
        assertTrue(attachments.started.isCompleted)
        profileJob.cancel()
        runCurrent()
        attachments.finish()
        operation.await()
        assertEquals(ResearchLoadState.NeedsUserAction, fixture.source().loadState)
        assertEquals(null, fixture.source().attachmentId)
    }

    @Test
    fun `source deletion cancels import and late attachment cannot recreate it`() = runTest {
        val attachments = PendingAttachments()
        val fixture = Fixture(backgroundScope, attachments)
        fixture.prepare()
        val operation = async { fixture.recover("source", ResearchRecoveryAction.Continue) }
        runCurrent()
        complete(fixture, fixture.machine.requests.single(), documentResult())
        fixture.storage.update("session") { session ->
            session.copy(resources = session.resources.filterNot { it.id == "source" })
        }
        runCurrent()
        attachments.finish()
        operation.await()
        assertEquals(listOf("other"), fixture.storage.read().single().resources.map { it.id })
    }

    @Test
    fun `either recovery flag revokes import and reenable cannot accept its late result`() = runTest {
        listOf(BrowserEnabled, BrowserResearchAccess).forEach { flag ->
            val attachments = PendingAttachments()
            val fixture = Fixture(backgroundScope, attachments)
            fixture.prepare()
            val operation = async { fixture.recover("source", ResearchRecoveryAction.Continue) }
            runCurrent()
            complete(fixture, fixture.machine.requests.single(), documentResult())
            assertTrue(attachments.started.isCompleted)
            fixture.toggles.set(flag, false)
            runCurrent()
            fixture.toggles.set(flag, true)
            attachments.finish()
            runCurrent()
            operation.await()
            assertEquals(ResearchLoadState.NeedsUserAction, fixture.source().loadState)
            assertEquals(null, fixture.source().attachmentId)
            assertEquals(null, fixture.source().recoveryAttempt)
        }
    }

    @Test
    fun `agent control flag does not revoke human document recovery`() = runTest {
        val attachments = PendingAttachments()
        val fixture = Fixture(backgroundScope, attachments)
        fixture.prepare()
        val operation = async { fixture.recover("source", ResearchRecoveryAction.Continue) }
        runCurrent()
        complete(fixture, fixture.machine.requests.single(), documentResult())
        fixture.toggles.set(BrowserAgentControl, false)
        runCurrent()
        attachments.finish()
        operation.await()
        assertEquals(ResearchLoadState.Ready, fixture.source().loadState)
        assertEquals(AttachmentId("download"), fixture.source().attachmentId)
    }

    @Test
    fun `retry waits for cancelled source cleanup while another source proceeds`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.prepare()
        val first = async { fixture.recover("source", ResearchRecoveryAction.Open) }
        runCurrent()
        val firstRequest = fixture.machine.requests.single()
        val previousAttempt = fixture.source().recoveryAttempt
        val cancellation = async { fixture.recover("source", ResearchRecoveryAction.Cancel) }
        runCurrent()
        assertEquals(listOf(firstRequest.id), fixture.machine.cancellations)
        val retry = async { fixture.recover("source", ResearchRecoveryAction.Open) }
        val unrelated = async { fixture.recover("other", ResearchRecoveryAction.Open) }
        runCurrent()
        assertFalse(cancellation.isCompleted)
        assertFalse(retry.isCompleted)
        assertEquals(2, fixture.machine.requests.size)
        val otherRequest = fixture.machine.requests.last()
        assertTrue(otherRequest.arguments.getValue("url").jsonPrimitive.content.endsWith("/other"))
        fixture.machine.outputs.emit(BrowserOutput.Cancelled(firstRequest.id))
        cancellation.await()
        // The user's cancellation is expected: the original live caller settles without an error.
        first.await()
        runCurrent()
        assertEquals(3, fixture.machine.requests.size)
        val retryRequest = fixture.machine.requests.last()
        complete(fixture, otherRequest)
        unrelated.await()
        complete(fixture, retryRequest)
        retry.await()
        val recovered = fixture.source()
        assertEquals(ResearchLoadState.NeedsUserAction, recovered.loadState)
        assertNotEquals(previousAttempt, recovered.recoveryAttempt)
        assertEquals("tab-${retryRequest.id}", recovered.browserTabId)
    }

    @Test
    fun `late continue open and cancellation preserve completed evidence without native work`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.prepare()
        fixture.storage.update("session") { session ->
            session.copy(
                resources = session.resources.map {
                    it.copy(text = "Evidence", loadState = ResearchLoadState.Ready)
                },
            )
        }
        val completed = fixture.source()
        ResearchRecoveryAction.entries.forEach { action ->
            fixture.recover("source", action)
            assertEquals(completed, fixture.source())
            assertTrue(fixture.machine.requests.isEmpty())
        }
        val newer = completed.copy(loadState = ResearchLoadState.Loading, recoveryAttempt = "new")
        assertEquals(newer, newer.cancelRecoveryAttempt("old"))
    }

    @Test
    fun `completed operation keeps its result without waiting for or requesting cancellation`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.prepare()
        val operation = async { fixture.recover("source", ResearchRecoveryAction.Open) }
        runCurrent()
        val request = fixture.machine.requests.single()
        fixture.machine.outputs.emit(
            BrowserOutput.Completed(BrowserResult(request.id, buildJsonObject { put("tabId", "opened") })),
        )
        operation.await()
        assertTrue(fixture.machine.cancellations.isEmpty())
        assertEquals("opened", fixture.source().browserTabId)
        assertEquals(ResearchLoadState.NeedsUserAction, fixture.source().loadState)
    }

    @Test
    fun `operation timeout is a retryable error and unconfirmed cancellation does not mask it`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.prepare()
        val operation = async { fixture.recover("source", ResearchRecoveryAction.Open) }
        runCurrent()
        val request = fixture.machine.requests.single()
        operation.await()
        assertEquals(listOf(request.id), fixture.machine.cancellations)
        assertEquals(ResearchLoadState.NeedsUserAction, fixture.source().loadState)
        assertEquals("browser_retry_required", fixture.source().loadError)
    }

    @Test
    fun `source changed during capture is not imported`() = runTest {
        val attachments = PendingAttachments()
        val fixture = Fixture(backgroundScope, attachments)
        fixture.prepare()
        val operation = async { fixture.recover("source", ResearchRecoveryAction.Continue) }
        runCurrent()
        fixture.storage.update("session") { session ->
            session.copy(
                resources = session.resources.map { if (it.id == "source") it.copy(title = "Renamed") else it },
            )
        }
        complete(fixture, fixture.machine.requests.single(), documentResult())
        operation.await()
        assertFalse(attachments.started.isCompleted)
        assertEquals(null, fixture.source().attachmentId)
    }

    private suspend fun TestScope.complete(
        fixture: Fixture,
        request: BrowserRequest,
        data: JsonObject = buildJsonObject { put("tabId", "tab-${request.id}") },
    ) {
        fixture.machine.outputs.emit(
            BrowserOutput.Completed(BrowserResult(request.id, data)),
        )
        runCurrent()
        fixture.machine.outputs.emit(BrowserOutput.Cancelled(request.id))
        runCurrent()
    }

    private fun documentResult(): JsonObject = buildJsonObject {
        put("kind", "document")
        put("mediaType", "application/pdf")
        put("contentBase64", "JVBERi0=")
        put("extension", "pdf")
    }

    private class PendingAttachments : ResearchAttachments by ResearchAttachments.Legacy {
        val started = CompletableDeferred<Unit>()
        private val result = CompletableDeferred<List<AttachmentDescriptor>>()

        override suspend fun import(
            inputs: List<AttachmentInput>,
            support: PromptInputSupport,
        ): List<AttachmentDescriptor> {
            started.complete(Unit)
            return result.await()
        }

        fun finish() {
            result.complete(listOf(AttachmentDescriptor(AttachmentId("download"), "source.pdf", "application/pdf", 5)))
        }
    }

    private class Fixture(scope: CoroutineScope, attachments: ResearchAttachments = ResearchAttachments.Legacy) {
        val storage = ExecutionStorage()
        val machine = BrowserMachine()
        val toggles = MutableToggles()
        private val recovery = ResearchBrowserRecovery(
            storage,
            BrowserRegistry(machine),
            toggles,
            attachments,
            Profile(scope),
        )

        suspend fun prepare() {
            val target = EngineTarget(EngineId("koog"), EngineBindingId("binding"), ModelId("model"))
            val sources = listOf("source", "other").map { id ->
                ResearchResource(
                    id,
                    id,
                    ResearchResourceKind.Website,
                    "https://example.com/$id",
                    "text/html",
                    loadState = ResearchLoadState.Failed,
                    browserTabId = "existing-tab",
                )
            }
            storage.add(ResearchSession("session", "", target, emptyList(), sources))
        }

        suspend fun recover(id: String, action: ResearchRecoveryAction) = recovery.recover("session", id, action)

        suspend fun source() = storage.read().single().resources.first { it.id == "source" }
    }

    private class BrowserMachine : MachineRef<BrowserState, BrowserIntent.Public, BrowserOutput> {
        override val name = "browser"
        override val state = MutableStateFlow<BrowserState>(BrowserState.Running(isEnabled = true))
        override val outputs = MutableSharedFlow<BrowserOutput>()
        val requests = mutableListOf<BrowserRequest>()
        val cancellations = mutableListOf<String>()

        override suspend fun send(intent: BrowserIntent.Public): SendResult {
            when (intent) {
                is BrowserIntent.Public.Perform -> requests += intent.request
                is BrowserIntent.Public.CancelOperation -> cancellations += intent.id
                else -> error("Unexpected browser request")
            }
            return SendResult.Accepted
        }
    }

    private class BrowserRegistry(private val machine: BrowserMachine) : MachineRegistry {
        @Suppress("UNCHECKED_CAST") // This fixture registers only the browser machine.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? {
            check(key == BrowserMachineKey)
            return machine as MachineRef<S, P, O>
        }

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = requireNotNull(find(key)).send(intent)
    }

    private class MutableToggles : FeatureToggles {
        private val values = mutableMapOf<FeatureToggle<*>, MutableStateFlow<Boolean>>()

        fun set(toggle: FeatureToggle<Boolean>, enabled: Boolean) {
            values.getOrPut(toggle) { MutableStateFlow(true) }.value = enabled
        }

        @Suppress("UNCHECKED_CAST") // Recovery only reads boolean flags.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
            values.getOrPut(toggle) { MutableStateFlow(true) }.value as T

        @Suppress("UNCHECKED_CAST") // Recovery only observes boolean flags.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
            values.getOrPut(toggle) { MutableStateFlow(true) } as Flow<T>
    }

    private class Profile(override val coroutineScope: CoroutineScope) : ScopeHandle {
        override val name = "recovery-profile"
        override val savedState: ScopeSavedState get() = error("Unused saved state")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
    }
}
