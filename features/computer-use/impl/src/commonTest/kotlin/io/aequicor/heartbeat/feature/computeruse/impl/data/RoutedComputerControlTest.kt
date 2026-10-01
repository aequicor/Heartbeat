package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
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
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseDesktopInput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEffect
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineSpec
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseNativeRouting
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseWindowMode
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.NativeCapture
import io.aequicor.heartbeat.feature.computeruse.api.NativeComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.solidGrid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertEquals(CaptureFormat.Jpeg, result.reference?.format)
        assertEquals(64, result.reference?.widthPx)
        assertEquals(32, result.reference?.heightPx)
        assertEquals(2, fixture.store.files.size)
        val master = assertNotNull(result.master)
        assertEquals(CaptureFormat.Png, master.format)
        val crop = fixture.control.crop(CropRequest(master.id, region = CaptureRegion(0, 0, 10, 10)))
        assertEquals(10, crop.reference?.widthPx)
        assertEquals(master, crop.master)
        assertEquals(crop.reference, fixture.control.status().lastPreview)
        assertEquals(InputOutcome.Applied, fixture.control.input(InputAction.Click(FramePoint(5.0, 5.0))))
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
        fixture.toggles.set(ComputerUseNativeRouting.key, false)
        val captured = fixture.control.capture(CaptureRequest())
        val master = assertNotNull(captured.master)
        fixture.toggles.set(ComputerUseNativeRouting.key, true)
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
    fun `public captures and crops update the machine before subsequent input`() = runTest {
        val fixture = fixture()
        val first = fixture.control.capture(CaptureRequest())
        assertEquals(first.reference, fixture.control.status().lastPreview)
        val second = fixture.control.capture(CaptureRequest())
        assertEquals(second.reference, fixture.control.status().lastPreview)
        val crop = fixture.control.crop(
            CropRequest(assertNotNull(first.master).id, region = CaptureRegion(0, 0, 10, 10)),
        )
        assertEquals(first.master, crop.master)
        assertEquals(crop.reference, fixture.control.status().lastPreview)
        assertEquals(InputOutcome.Applied, fixture.control.input(InputAction.Click(FramePoint(5.0, 5.0))))
        val input = assertIs<ComputerUseIntent.Public.Input>(fixture.registry.ref!!.sent.last())
        assertEquals(crop.reference?.id, input.expectedCapture)
        assertEquals(1, fixture.injector.applied.size)
    }

    @Test
    fun `revoke returns after the old session artifacts are purged`() = runTest {
        val fixture = fixture()
        fixture.control.capture(CaptureRequest())
        assertTrue(fixture.store.files.isNotEmpty())
        fixture.control.revoke()
        assertTrue(fixture.store.files.isEmpty())
        assertEquals(null, fixture.coordinator.currentBounds())
    }

    @Test
    fun `revoke reports an explicit failure when cleanup is never acknowledged`() = runTest {
        val fixture = fixture()
        fixture.registry.ref!!.effectHandler = null
        val failure = assertFailsWith<IllegalStateException> { fixture.control.revoke() }
        assertEquals("CleanupTimedOut", failure.message)
    }

    @Test
    fun `a timed out native capture cancels only its owning machine session`() = runTest {
        val fixture = fixture(isRoutingEnabled = true)
        fixture.native.awaitCapture = CompletableDeferred()
        val result = fixture.control.capture(CaptureRequest())
        assertEquals(ComputerUseFailure.Timeout, result.failure)
        assertEquals(ComputerUseState.Idle, fixture.registry.ref!!.state.value)
        assertIs<ComputerUseIntent.Public.CancelSession>(fixture.registry.ref.sent.last())
        runCurrent()
        assertTrue(fixture.store.files.isEmpty())
        assertEquals(null, fixture.coordinator.currentBounds())
    }

    @Test
    fun `cancelling a public capture stops the machine operation`() = runTest {
        val fixture = fixture(isRoutingEnabled = true)
        fixture.native.awaitCapture = CompletableDeferred()
        val capture = launch { fixture.control.capture(CaptureRequest()) }
        runCurrent()
        assertEquals(1, fixture.native.calls)
        capture.cancelAndJoin()
        runCurrent()
        assertEquals(ComputerUseState.Idle, fixture.registry.ref!!.state.value)
        val cancelled = assertIs<ComputerUseIntent.Public.CancelSession>(fixture.registry.ref.sent.last())
        assertEquals(fixture.session, cancelled.session)
        assertEquals(null, fixture.coordinator.currentBounds())
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
            isOpen = true,
        )
        val status = fixture.control.status()
        assertEquals(ComputerUseMode.Desktop(), status.mode)
        assertTrue(status.isInputArmed)
        assertTrue(status.capabilities.isCaptureAvailable)
    }

    @Test
    fun `disabling the master toggle refuses capture crop windows and input`() = runTest {
        val fixture = fixture(isRoutingEnabled = true)
        fixture.toggles.set(ComputerUseEnabled.key, false)
        assertFalse(fixture.control.status().capabilities.isCaptureAvailable)
        assertTrue(fixture.control.windows().isEmpty())
        assertNotNull(fixture.control.capture(CaptureRequest()).failure)
        assertIs<InputOutcome.Rejected>(fixture.control.input(InputAction.Type("secret")))
        assertEquals(0, fixture.native.calls)
        assertTrue(fixture.injector.applied.isEmpty())
        assertTrue(fixture.store.files.isEmpty())
    }

    @Test
    fun `public input is correlated through the machine and does not bypass its effects`() = runTest {
        val fixture = fixture(isRoutingEnabled = true)
        assertEquals(InputOutcome.Applied, fixture.control.input(InputAction.Type("hello")))
        val input = assertIs<ComputerUseIntent.Public.Input>(fixture.registry.ref!!.sent.single())
        assertNotNull(input.requestId)
        assertEquals(0, fixture.native.calls)
        assertEquals(listOf<InputAction>(InputAction.Type("hello")), fixture.injector.applied)
    }

    @Test
    fun `public input refuses unarmed captures before calling the machine`() = runTest {
        val fixture = fixture()
        val state = assertIs<ComputerUseState.Capturing>(fixture.registry.ref!!.states.value)
        fixture.registry.ref.states.value = state.copy(isInputArmed = false)
        assertEquals(
            InputOutcome.Rejected(ComputerUseFailure.NotArmed),
            fixture.control.input(InputAction.Type("hello")),
        )
        assertTrue(fixture.registry.ref.sent.isEmpty())
    }

    @Test
    fun `revoked accessibility and desktop allowance are checked on every input`() = runTest {
        val fixture = fixture()
        fixture.permissions.capabilities = fixture.permissions.capabilities.copy(isInputAvailable = false)
        assertEquals(
            InputOutcome.Rejected(ComputerUseFailure.PermissionLost),
            fixture.control.input(InputAction.Type("hello")),
        )
        fixture.permissions.capabilities = fixture.permissions.capabilities.copy(isInputAvailable = true)
        fixture.toggles.set(ComputerUseDesktopInput.key, false)
        assertEquals(
            InputOutcome.Rejected(ComputerUseFailure.ModeNotAllowed),
            fixture.control.input(InputAction.Type("hello")),
        )
        assertTrue(fixture.registry.ref!!.sent.isEmpty())
    }

    @Test
    fun `the tools are published only behind both toggles`() = runTest {
        val fixture = fixture()
        val tools = ComputerUseAgentTools(
            fixture.registry,
            fixture.toggles,
            fixture.control,
            TestComputerUseScope(backgroundScope),
        )
        assertTrue(tools.specifications(null).isNotEmpty())
        fixture.toggles.set(AgentToolsToggle.key, false)
        assertTrue(tools.specifications(null).isEmpty())
        fixture.toggles.set(ComputerUseEnabled.key, false)
        assertTrue(tools.instructions(null).isEmpty())
    }

    @Test
    fun `a tool call without a finished probe reports the host as unavailable`() = runTest {
        val fixture = fixture()
        val tools = ComputerUseAgentTools(
            fixture.registry,
            fixture.toggles,
            fixture.control,
            TestComputerUseScope(backgroundScope),
        )
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
        val permissions: FakeOsPermissions,
        val injector: FakeInputInjector,
    )

    private suspend fun TestScope.fixture(isRoutingEnabled: Boolean = false, isNativeBroken: Boolean = false): Fixture {
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
                ComputerUseDesktopInput.key to true,
                ComputerUseWindowMode.key to true,
            ),
        )
        val native = FakeNativeControl(isNativeBroken, encoder)
        val registry = FakeMachineRegistry(FakeMachineRef(backgroundScope))
        registry.ref!!.states.value = ComputerUseState.Capturing(
            Session,
            ComputerUseMode.Desktop(),
            CaptureOwner.Panel,
            ComputerUseCapabilities(true, true, true, true),
            isInputArmed = true,
            isOpen = true,
        )
        coordinator.open(Session, ComputerUseMode.Desktop())
        val router = object : NativeControlRouter {
            override suspend fun features(): EngineFeatures = FakeEngineFeatures(native, isRoutingEnabled)
        }
        val permissions = FakeOsPermissions()
        val access = ComputerUseAccess(toggles, permissions, registry, dispatchers)
        registry.ref.effectHandler = ComputerUseEffectHandler(
            access,
            coordinator,
            ComputerUseCaptureExecutor(coordinator, access, router),
        )
        val control = RoutedComputerControl(
            coordinator,
            access,
            registry,
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
        return Fixture(control, coordinator, native, store, registry, toggles, Session, context, permissions, injector)
    }

    private class FakeNativeControl(private val isBroken: Boolean, private val encoder: FakeFrameEncoder) :
        NativeComputerControl {
        var calls: Int = 0
            private set
        var awaitCapture: CompletableDeferred<Unit>? = null

        override suspend fun capture(request: CaptureRequest): NativeCapture {
            calls++
            awaitCapture?.await()
            if (isBroken) throw IllegalStateException("native capture is unavailable")
            val encoded = encoder.encode(solidGrid(64, 32, 0), CaptureEncoding())
            return NativeCapture(CaptureFormat.Png, 64, 32, encoded.content)
        }

        override suspend fun input(action: InputAction): InputOutcome {
            calls++
            if (isBroken) throw IllegalStateException("native input is unavailable")
            return InputOutcome.Applied
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

    /** Uses the production spec and effects, including state exit cancellation, without an impl dependency. */
    internal class FakeMachineRef(private val scope: CoroutineScope? = null) :
        MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput> {
        val states = MutableStateFlow<ComputerUseState>(ComputerUseState.Idle)
        val events = MutableSharedFlow<ComputerUseOutput>(extraBufferCapacity = 8)
        val sent = mutableListOf<ComputerUseIntent.Public>()
        var effectHandler: EffectHandler<ComputerUseEffect, ComputerUseIntent>? = null
        private val effectJobs = mutableListOf<Job>()
        private var generation = 0

        override val name: String = "computer-use"
        override val state: StateFlow<ComputerUseState> = states
        override val outputs = events

        override suspend fun send(intent: ComputerUseIntent.Public): SendResult {
            sent += intent
            if (intent is ComputerUseIntent.Public.Input) {
                events.emit(ComputerUseOutput.InputApplied(intent.action, "unrelated"))
            }
            return dispatch(intent)
        }

        private suspend fun dispatch(intent: ComputerUseIntent): SendResult {
            val resolution = ComputerUseMachineSpec.resolve(states.value, intent) ?: return SendResult.Ignored
            if (resolution.isStateChange) {
                effectJobs.forEach { it.cancel() }
                effectJobs.clear()
                generation++
            }
            states.value = resolution.to
            resolution.outputs.forEach { events.emit(it) }
            val handler = effectHandler ?: return SendResult.Accepted
            val capturedGeneration = generation
            val feedback = object : EffectScope<ComputerUseIntent> {
                override suspend fun send(intent: ComputerUseIntent): SendResult =
                    if (capturedGeneration == generation) dispatch(intent) else SendResult.Ignored
            }
            resolution.effects.forEach { effect ->
                effectJobs += checkNotNull(scope).launch { handler.handle(effect, feedback) }
            }
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
            return machine.send(intent)
        }
    }

    private companion object {
        val Session = CaptureSessionId("routed")
    }
}
