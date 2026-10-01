package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseNativeRouting
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.NativeCapture
import io.aequicor.heartbeat.feature.computeruse.api.NativeComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseAgentTools as AgentToolsToggle

class RoutedComputerControlTest {

    @Test
    fun `the host serves captures while native routing is off`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(fixture.session, ComputerUseMode.Desktop())
        val result = fixture.control.capture(CaptureRequest())
        assertEquals(0, fixture.native.calls)
        assertNotNull(result.reference)
        assertEquals(200, result.reference?.widthPx)
        assertTrue(fixture.store.files.isNotEmpty())
    }

    @Test
    fun `a native engine capture is stored as a host frame when routing is on`() = runTest {
        val fixture = fixture(isRoutingEnabled = true)
        val result = fixture.control.capture(CaptureRequest())
        assertEquals(1, fixture.native.calls)
        assertNotNull(result.reference)
        assertEquals(CaptureFormat.Png, result.reference?.format)
        assertEquals(64, result.reference?.widthPx)
        assertEquals(32, result.reference?.heightPx)
        assertEquals(1, fixture.store.files.size)
    }

    @Test
    fun `a failing native capture falls back to the host`() = runTest {
        val fixture = fixture(isRoutingEnabled = true, isNativeBroken = true)
        fixture.coordinator.open(fixture.session, ComputerUseMode.Desktop())
        val result = fixture.control.capture(CaptureRequest())
        assertEquals(1, fixture.native.calls)
        assertNotNull(result.reference)
        assertEquals(200, result.reference?.widthPx)
    }

    @Test
    fun `crops are always served by the host`() = runTest {
        val fixture = fixture(isRoutingEnabled = true)
        fixture.coordinator.open(fixture.session, ComputerUseMode.Desktop())
        val captured = fixture.coordinator.capture(CaptureRequest())
        val master = assertNotNull(assertIs<CaptureOutcome.Produced>(captured).result.master)
        val crop = fixture.control.crop(CropRequest(master.id, region = CaptureRegion(0, 0, 10, 10)))
        assertEquals(0, fixture.native.calls)
        assertEquals(10, crop.reference?.widthPx)
    }

    @Test
    fun `revoke goes through the machine when it runs`() = runTest {
        val fixture = fixture()
        fixture.control.revoke()
        val sent = fixture.registry.ref?.sent.orEmpty()
        assertEquals(listOf<ComputerUseIntent.Public>(ComputerUseIntent.Public.Revoke), sent)
    }

    @Test
    fun `the status mirrors the machine state`() = runTest {
        val fixture = fixture()
        fixture.registry.ref?.states?.value = ComputerUseState.Capturing(
            session = fixture.session,
            mode = ComputerUseMode.Desktop(),
            owner = CaptureOwner.Panel,
            capabilities = ComputerUseCapabilities(true, true, true, true),
            isInputArmed = true,
        )
        val status = fixture.control.status()
        assertEquals(ComputerUseMode.Desktop(), status.mode)
        assertTrue(status.isInputArmed)
        assertTrue(status.capabilities.isCaptureAvailable)
    }

    @Test
    fun `the tools are published only behind both toggles`() = runTest {
        val fixture = fixture()
        val tools = ComputerUseAgentTools(fixture.registry, fixture.toggles, fixture.control)
        assertTrue(tools.specifications(null).isNotEmpty())
        fixture.toggles.set(AgentToolsToggle.key, false)
        assertTrue(tools.specifications(null).isEmpty())
        fixture.toggles.set(ComputerUseEnabled.key, false)
        assertTrue(tools.instructions(null).isEmpty())
    }

    @Test
    fun `a tool call without a finished probe reports the host as unavailable`() = runTest {
        val fixture = fixture()
        val tools = ComputerUseAgentTools(fixture.registry, fixture.toggles, fixture.control)
        val result = tools.execute(
            fixture.context,
            "computer_status",
            kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        assertTrue(result.text.isNotEmpty())
    }

    private class Fixture(
        val control: RoutedComputerControl,
        val coordinator: CaptureCoordinator,
        val native: FakeNativeControl,
        val store: FakeFrameStore,
        val registry: FakeMachineRegistry,
        val toggles: FakeToggles,
        val session: CaptureSessionId,
        val context: io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext,
    )

    private fun TestScope.fixture(isRoutingEnabled: Boolean = false, isNativeBroken: Boolean = false): Fixture {
        val capturer = FakeScreenCapturer()
        val windows = FakeWindowCatalog(listOf(windowTarget()))
        val injector = FakeInputInjector()
        val store = FakeFrameStore()
        val encoder = FakeFrameEncoder()
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val pipeline = FramePipeline(encoder, store, VisionBudget(maxTokens = 100_000), dispatchers)
        val cache = MasterFrameCache(encoder, store, maxBytes = 8L * 1024 * 1024)
        val coordinator = CaptureCoordinator(capturer, windows, injector, pipeline, cache, dispatchers)
        val toggles = FakeToggles(
            mapOf(
                ComputerUseEnabled.key to true,
                AgentToolsToggle.key to true,
                ComputerUseNativeRouting.key to isRoutingEnabled,
            ),
        )
        val native = FakeNativeControl(isNativeBroken)
        val registry = FakeMachineRegistry(FakeMachineRef())
        val router = object : NativeControlRouter {
            override suspend fun features(): EngineFeatures = FakeEngineFeatures(native, isRoutingEnabled)
        }
        val control = RoutedComputerControl(
            coordinator,
            FakeOsPermissions(),
            toggles,
            router,
            registry,
            store,
            VisionBudget(maxTokens = 1200),
            dispatchers,
        )
        val context = io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext(
            session = io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef(
                io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId("pi"),
                io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId("source"),
                "native",
            ),
            workspace = null,
            turn = io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId("turn"),
        )
        return Fixture(control, coordinator, native, store, registry, toggles, Session, context)
    }

    private class FakeNativeControl(private val isBroken: Boolean) : NativeComputerControl {
        var calls: Int = 0
            private set

        override suspend fun capture(request: CaptureRequest): NativeCapture {
            calls++
            if (isBroken) throw IllegalStateException("native capture is unavailable")
            return NativeCapture(CaptureFormat.Png, 64, 32, ByteArray(NATIVE_BYTES) { it.toByte() })
        }

        override suspend fun input(action: InputAction): InputOutcome {
            calls++
            if (isBroken) throw IllegalStateException("native input is unavailable")
            return InputOutcome.Applied
        }

        private companion object {
            const val NATIVE_BYTES = 12
        }
    }

    private class FakeEngineFeatures(private val native: NativeComputerControl, private val isDeclared: Boolean) :
        EngineFeatures {
        @Suppress("UNCHECKED_CAST") // tests resolve one known key
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
            if (isDeclared && key == NativeComputerControl) {
                FeatureAccess.Available(native as F)
            } else {
                FeatureAccess.Unsupported
            }
    }

    internal class FakeMachineRef : MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput> {
        val states = MutableStateFlow<ComputerUseState>(ComputerUseState.Idle)
        val events = MutableSharedFlow<ComputerUseOutput>(extraBufferCapacity = 8)
        val sent = mutableListOf<ComputerUseIntent.Public>()

        override val name: String = "computer-use"
        override val state: StateFlow<ComputerUseState> = states
        override val outputs = events

        override suspend fun send(intent: ComputerUseIntent.Public): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    internal class FakeMachineRegistry(val ref: FakeMachineRef?) : MachineRegistry {
        @Suppress("UNCHECKED_CAST") // tests address one known machine key
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? = ref as MachineRef<S, P, O>?

        @Suppress("UNCHECKED_CAST") // tests address one known machine key
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(ref as MachineRef<S, P, O>?)

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult {
            val machine = find(key) ?: return SendResult.NotRunning
            machine.send(intent)
            return SendResult.Accepted
        }
    }

    private companion object {
        val Session = CaptureSessionId("routed")
    }
}
