package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseNativeRouting
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseWindowMode
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.MonitorId
import io.aequicor.heartbeat.feature.computeruse.api.NativeCapture
import io.aequicor.heartbeat.feature.computeruse.api.NativeComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.solidGrid
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComputerUseCaptureExecutorTest {
    @Test
    fun `native full frames retain their master geometry and apply the requested region only once`() = runTest {
        val fixture = fixture(ComputerUseMode.Desktop())
        val region = CaptureRegion(40, 16, 20, 10)
        val request = CaptureRequest(
            region = region,
            encoding = CaptureEncoding(maxWidthPx = 10, maxHeightPx = 10),
            isCursorIncluded = false,
            isFresh = false,
        )
        val result = fixture.executor.capture(request)
        val master = assertNotNull(result.master)
        val preview = assertNotNull(result.reference)
        assertEquals(64, master.masterWidthPx)
        assertEquals(32, master.masterHeightPx)
        assertEquals(region, preview.region)
        assertTrue(preview.widthPx <= 10 && preview.heightPx <= 10)
        assertEquals(0, fixture.capturer.captures)
        assertEquals(
            request.copy(region = null, tile = null, encoding = CapturePresets.Master),
            fixture.native.requests.single(),
        )
    }

    @Test
    fun `window and selected monitor targets are captured by the host`() = runTest {
        val modes = listOf(
            ComputerUseMode.Window(windowTarget(), isClientAreaOnly = false),
            ComputerUseMode.Desktop(monitor = MonitorId("secondary")),
        )
        modes.forEach { mode ->
            val fixture = fixture(mode)
            val result = fixture.executor.capture(CaptureRequest())
            assertNotNull(result.reference)
            assertEquals(1, fixture.capturer.captures)
            assertTrue(fixture.native.requests.isEmpty())
        }
    }

    private class NativeFrames(private val encoder: FakeFrameEncoder) : NativeComputerControl {
        val requests = mutableListOf<CaptureRequest>()

        override suspend fun capture(request: CaptureRequest): NativeCapture {
            requests += request
            val full = solidGrid(64, 32, 0)
            val pixels = request.region?.let(full::region) ?: full
            val encoded = encoder.encode(pixels, request.encoding)
            return NativeCapture(encoded.format, encoded.widthPx, encoded.heightPx, encoded.content)
        }

        override suspend fun input(action: InputAction): InputOutcome =
            InputOutcome.Rejected(ComputerUseFailure.Unavailable)
    }

    private class NativeFeatures(private val native: NativeComputerControl) : EngineFeatures {
        @Suppress("UNCHECKED_CAST") // This fake exposes only the NativeComputerControl key.
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
            if (key == NativeComputerControl) FeatureAccess.Available(native as F) else FeatureAccess.Unsupported
    }

    private class Fixture(
        val executor: ComputerUseCaptureExecutor,
        val native: NativeFrames,
        val capturer: FakeScreenCapturer,
    )

    private suspend fun TestScope.fixture(mode: ComputerUseMode): Fixture {
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val encoder = FakeFrameEncoder()
        val store = FakeFrameStore()
        val capturer = FakeScreenCapturer()
        val coordinator = CaptureCoordinator(
            capturer,
            FakeWindowCatalog(listOf(windowTarget())),
            FakeInputInjector(),
            FramePipeline(encoder, store, VisionBudget(100_000), dispatchers),
            MasterFrameCache(encoder, store, 8L * 1024 * 1024),
            dispatchers,
        )
        val session = CaptureSessionId("native-routing")
        coordinator.open(session, mode)
        val machine = RoutedComputerControlTest.FakeMachineRef()
        machine.states.value = ComputerUseState.Capturing(
            session,
            mode,
            CaptureOwner.Panel,
            ComputerUseCapabilities(true, true, true, true),
            isOpen = true,
        )
        val registry = RoutedComputerControlTest.FakeMachineRegistry(machine)
        val toggles = FakeToggles(
            mapOf(
                ComputerUseEnabled.key to true,
                ComputerUseWindowMode.key to true,
                ComputerUseNativeRouting.key to true,
            ),
        )
        val access = ComputerUseAccess(toggles, FakeOsPermissions(), registry, dispatchers)
        val native = NativeFrames(encoder)
        val router = NativeControlRouter { NativeFeatures(native) }
        return Fixture(ComputerUseCaptureExecutor(coordinator, access, router), native, capturer)
    }
}
