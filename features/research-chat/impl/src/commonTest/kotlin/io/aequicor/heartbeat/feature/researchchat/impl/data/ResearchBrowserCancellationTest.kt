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
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchRecoveryAction
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResearchBrowserCancellationTest {
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
        assertFailsWith<CancellationException> { first.await() }
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
    fun `late cancellation preserves completed evidence and does not dispatch native work`() = runTest {
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
        fixture.recover("source", ResearchRecoveryAction.Cancel)
        assertEquals(completed, fixture.source())
        assertTrue(fixture.machine.requests.isEmpty())
        val newer = completed.copy(loadState = ResearchLoadState.Loading, recoveryAttempt = "new")
        assertEquals(newer, newer.cancelRecoveryAttempt("old"))
    }

    private suspend fun TestScope.complete(fixture: Fixture, request: BrowserRequest) {
        fixture.machine.outputs.emit(
            BrowserOutput.Completed(BrowserResult(request.id, buildJsonObject { put("tabId", "tab-${request.id}") })),
        )
        runCurrent()
        fixture.machine.outputs.emit(BrowserOutput.Cancelled(request.id))
        runCurrent()
    }

    private class Fixture(scope: CoroutineScope) {
        val storage = ExecutionStorage()
        val machine = BrowserMachine()
        private val recovery = ResearchBrowserRecovery(
            storage,
            BrowserRegistry(machine),
            EnabledToggles(),
            ResearchAttachments.Legacy,
            Profile(scope),
        )

        suspend fun prepare() {
            val target = EngineTarget(EngineId("koog"), EngineBindingId("binding"), ModelId("model"))
            val sources = listOf("source", "other").map { id ->
                ResearchResource(id, id, ResearchResourceKind.Website, "https://example.com/$id", "text/html")
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

    private class EnabledToggles : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // Recovery only reads boolean flags.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = true as T

        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flow { emit(get(toggle)) }
    }

    private class Profile(override val coroutineScope: CoroutineScope) : ScopeHandle {
        override val name = "recovery-profile"
        override val savedState: ScopeSavedState get() = error("Unused saved state")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
    }
}
