package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.NormalizedRegion
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.solidGrid
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturePipelineTest {

    @Test
    fun `a master frame is stored lossless at its full size`() = runTest {
        val store = FakeFrameStore()
        val pipeline = pipeline(store)
        val produced = pipeline.storeMaster(Session, CaptureId("m"), grid(200, 100), CaptureRegion(0, 0, 200, 100), 1)
        assertNotNull(produced)
        assertEquals(CaptureFormat.Png, produced.reference.format)
        assertEquals(200, produced.reference.widthPx)
        assertEquals(100, produced.reference.heightPx)
        assertTrue(produced.reference.isMaster)
        assertEquals(1, store.files.size)
    }

    @Test
    fun `a derived frame respects the width limit`() = runTest {
        val produced = pipeline(FakeFrameStore()).derive(
            session = Session,
            id = CaptureId("p"),
            source = grid(200, 100),
            region = CaptureRegion(0, 0, 200, 100),
            masterWidthPx = 200,
            masterHeightPx = 100,
            encoding = CaptureEncoding(format = CaptureFormat.Jpeg, maxWidthPx = 100, maxHeightPx = 100),
            sequence = 2,
        )
        assertNotNull(produced)
        assertEquals(100, produced.reference.widthPx)
        assertEquals(50, produced.reference.heightPx)
        assertEquals(0.5, produced.reference.previewScale)
    }

    @Test
    fun `a derived frame is reduced to the token budget`() = runTest {
        val budget = VisionBudget(maxTokens = 10)
        val pipeline = FramePipeline(FakeFrameEncoder(), FakeFrameStore(), budget, dispatchers())
        val produced = pipeline.derive(
            session = Session,
            id = CaptureId("p"),
            source = grid(2000, 1000),
            region = CaptureRegion(0, 0, 2000, 1000),
            masterWidthPx = 2000,
            masterHeightPx = 1000,
            encoding = CaptureEncoding(format = CaptureFormat.Png),
            sequence = 3,
        )
        assertNotNull(produced)
        assertTrue(budget.isAffordable(produced.reference.widthPx, produced.reference.heightPx))
        assertTrue(produced.reference.widthPx < 2000)
    }

    @Test
    fun `the ladder lowers the quality until the byte limit is met`() = runTest {
        val encoder = FakeFrameEncoder()
        val pipeline = FramePipeline(encoder, FakeFrameStore(), VisionBudget(maxTokens = 100_000), dispatchers())
        val produced = pipeline.derive(
            session = Session,
            id = CaptureId("p"),
            source = grid(400, 300),
            region = CaptureRegion(0, 0, 400, 300),
            masterWidthPx = 400,
            masterHeightPx = 300,
            encoding = CaptureEncoding(format = CaptureFormat.Jpeg, quality = 90, maxBytes = 40_000),
            sequence = 4,
        )
        assertNotNull(produced)
        assertTrue(produced.reference.bytes <= 40_000)
        assertTrue(encoder.encoded.size > 1, "the ladder should have retried")
    }

    @Test
    fun `an unreachable byte limit is reported as a failure`() = runTest {
        val encoder = FakeFrameEncoder().apply { minimumBytes = 1_000_000 }
        val pipeline = FramePipeline(encoder, FakeFrameStore(), VisionBudget(maxTokens = 100_000), dispatchers())
        val produced = pipeline.derive(
            session = Session,
            id = CaptureId("p"),
            source = grid(100, 100),
            region = CaptureRegion(0, 0, 100, 100),
            masterWidthPx = 100,
            masterHeightPx = 100,
            encoding = CaptureEncoding(maxBytes = 1024),
            sequence = 5,
        )
        assertNull(produced)
    }

    @Test
    fun `an evicted master buffer is decoded again from the store`() = runTest {
        val store = FakeFrameStore()
        val cache = MasterFrameCache(FakeFrameEncoder(), store, maxBytes = 1)
        cache.put(masterReference(CaptureId("m"), store), grid(4, 4))
        assertNotNull(cache.pixels(CaptureId("m")), "the frame must be reloaded from the store")
        assertEquals(1, cache.size())
    }

    @Test
    fun `forgetting a session deletes its stored frames`() = runTest {
        val store = FakeFrameStore()
        val cache = MasterFrameCache(FakeFrameEncoder(), store, maxBytes = 1_000_000)
        cache.put(masterReference(CaptureId("m"), store), grid(4, 4))
        assertTrue(store.files.isNotEmpty())
        cache.forgetSession(Session)
        assertTrue(store.files.isEmpty())
        assertEquals(0, cache.size())
        assertNull(cache.pixels(CaptureId("m")))
    }

    @Test
    fun `failed artifact deletion reports failure and preserves retry metadata`() = runTest {
        val store = FakeFrameStore()
        val cache = MasterFrameCache(FakeFrameEncoder(), store, maxBytes = 1_000_000)
        cache.put(masterReference(CaptureId("m"), store), grid(4, 4))
        store.deleteFailure = IllegalStateException("disk unavailable")
        assertFailsWith<IllegalStateException> { cache.forgetSession(Session) }
        assertEquals(1, cache.size())
        store.deleteFailure = null
        cache.forgetSession(Session)
        assertEquals(0, cache.size())
        assertTrue(store.files.isEmpty())
    }

    private fun TestScope.pipeline(store: FakeFrameStore): FramePipeline =
        FramePipeline(FakeFrameEncoder(), store, VisionBudget(maxTokens = 100_000), dispatchers())

    private fun TestScope.dispatchers(): TestDispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))

    private fun masterReference(id: CaptureId, store: FakeFrameStore): CaptureRef {
        val path = "${Session.value}/${id.value}.png"
        store.files[path] = ByteArray(HEADER_BYTES)
        return CaptureRef(
            id = id,
            session = Session,
            format = CaptureFormat.Png,
            widthPx = 4,
            heightPx = 4,
            region = CaptureRegion(0, 0, 4, 4),
            masterWidthPx = 4,
            masterHeightPx = 4,
            bytes = HEADER_BYTES.toLong(),
            estimatedTokens = 1,
            path = path,
            sequence = 1,
            isMaster = true,
        )
    }

    private fun grid(widthPx: Int, heightPx: Int): PixelGrid = solidGrid(widthPx, heightPx, 0xFF224466.toInt())

    private companion object {
        val Session = CaptureSessionId("s1")
        const val HEADER_BYTES = 16
    }
}

class CaptureCoordinatorTest {

    @Test
    fun `capture stores a master and returns a reduced preview`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        val outcome = fixture.coordinator.capture(CaptureRequest(encoding = fixture.smallJpeg))
        val result = assertIs<CaptureOutcome.Produced>(outcome).result
        val reference = assertNotNull(result.reference)
        assertEquals(200, assertNotNull(result.master).masterWidthPx)
        assertEquals(100, reference.widthPx)
        assertEquals(50, reference.heightPx)
        assertNull(result.tiles, "a small frame needs no tile grid")
        assertTrue(fixture.store.files.size >= 2)
    }

    @Test
    fun `a wide frame is served with a tile grid`() = runTest {
        val fixture = fixture(widthPx = 1100, heightPx = 600)
        fixture.coordinator.open(Session, Desktop)
        val outcome = fixture.coordinator.capture(CaptureRequest(encoding = fixture.smallJpeg))
        val tiles = assertIs<CaptureOutcome.Produced>(outcome).result.tiles
        assertNotNull(tiles)
        assertEquals(2, tiles.columns)
    }

    @Test
    fun `a crop is cut from the stored master at native resolution`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        val captured = assertIs<CaptureOutcome.Produced>(fixture.coordinator.capture(CaptureRequest()))
        val master = captured.result.master ?: error("master frame is missing")
        val crop = fixture.coordinator.crop(CropRequest(master.id, region = CaptureRegion(10, 10, 40, 20)))
        val reference = assertIs<CaptureOutcome.Produced>(crop).result.reference ?: error("crop is missing")
        assertEquals(40, reference.widthPx)
        assertEquals(20, reference.heightPx)
        assertEquals(200, reference.masterWidthPx)
        assertEquals(CaptureRegion(10, 10, 40, 20), reference.region)
        assertEquals(1, fixture.capturer.captures, "a crop must not capture the screen again")
    }

    @Test
    fun `a normalized crop maps into master pixels`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        val master = assertNotNull(
            assertIs<CaptureOutcome.Produced>(fixture.coordinator.capture(CaptureRequest())).result.master,
        )
        val crop = fixture.coordinator.crop(
            CropRequest(master.id, normalized = NormalizedRegion(0.25, 0.25, 0.5, 0.5)),
        )
        val reference = assertNotNull(assertIs<CaptureOutcome.Produced>(crop).result.reference)
        assertEquals(CaptureRegion(50, 25, 100, 50), reference.region)
    }

    @Test
    fun `an upscaled crop keeps its requested scale`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        val master = assertNotNull(
            assertIs<CaptureOutcome.Produced>(fixture.coordinator.capture(CaptureRequest())).result.master,
        )
        val crop = fixture.coordinator.crop(
            CropRequest(master.id, region = CaptureRegion(0, 0, 20, 10), scale = 2.0),
        )
        val reference = assertNotNull(assertIs<CaptureOutcome.Produced>(crop).result.reference)
        assertEquals(40, reference.widthPx)
        assertEquals(20, reference.heightPx)
    }

    @Test
    fun `cropping an unknown frame is refused`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        fixture.coordinator.capture(CaptureRequest())
        val outcome = fixture.coordinator.crop(CropRequest(CaptureId("absent"), CaptureRegion(0, 0, 10, 10)))
        assertEquals(ComputerUseFailure.UnknownCapture, assertIs<CaptureOutcome.Rejected>(outcome).reason)
    }

    @Test
    fun `a crop outside the master is refused`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        val master = assertNotNull(
            assertIs<CaptureOutcome.Produced>(fixture.coordinator.capture(CaptureRequest())).result.master,
        )
        val outcome = fixture.coordinator.crop(CropRequest(master.id, CaptureRegion(180, 0, 40, 10)))
        assertEquals(ComputerUseFailure.RegionOutOfBounds, assertIs<CaptureOutcome.Rejected>(outcome).reason)
    }

    @Test
    fun `after a purge the same crop is refused and the files are gone`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        val master = assertNotNull(
            assertIs<CaptureOutcome.Produced>(fixture.coordinator.capture(CaptureRequest())).result.master,
        )
        fixture.coordinator.close()
        fixture.coordinator.purge()
        fixture.coordinator.open(Session, Desktop)
        val outcome = fixture.coordinator.crop(CropRequest(master.id, CaptureRegion(0, 0, 10, 10)))
        assertEquals(ComputerUseFailure.UnknownCapture, assertIs<CaptureOutcome.Rejected>(outcome).reason)
        assertTrue(fixture.store.files.isEmpty())
    }

    @Test
    fun `capture without a session is refused`() = runTest {
        val fixture = fixture()
        assertEquals(
            ComputerUseFailure.Unavailable,
            assertIs<CaptureOutcome.Rejected>(fixture.coordinator.capture(CaptureRequest())).reason,
        )
    }

    @Test
    fun `a vanished target is reported instead of a stale frame`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        fixture.capturer.bounds = null
        val outcome = fixture.coordinator.capture(CaptureRequest())
        assertEquals(ComputerUseFailure.TargetClosed, assertIs<CaptureOutcome.Rejected>(outcome).reason)
    }

    @Test
    fun `input maps preview coordinates into screen pixels`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        fixture.coordinator.capture(CaptureRequest(encoding = fixture.smallJpeg))
        val outcome = fixture.coordinator.input(InputAction.Click(FramePoint(10.0, 10.0)))
        assertEquals(InputOutcome.Applied, outcome)
        // The preview is half the master, and the master starts at the captured origin 10,20.
        assertEquals(listOf(ScreenPoint(30, 40)), fixture.injector.points)
    }

    @Test
    fun `input outside the captured area is refused before it is injected`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        fixture.coordinator.capture(CaptureRequest(encoding = fixture.smallJpeg))
        val outcome = fixture.coordinator.input(InputAction.Click(FramePoint(500.0, 10.0)))
        assertEquals(ComputerUseFailure.RegionOutOfBounds, assertIs<InputOutcome.Rejected>(outcome).reason)
        assertTrue(fixture.injector.applied.isEmpty())
    }

    @Test
    fun `input in screen space is confined to the captured rectangle`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        fixture.coordinator.capture(CaptureRequest())
        val inside = fixture.coordinator.input(InputAction.Click(FramePoint(50.0, 60.0), space = FrameSpace.Screen))
        assertEquals(InputOutcome.Applied, inside)
        val outside = fixture.coordinator.input(InputAction.Click(FramePoint(5.0, 5.0), space = FrameSpace.Screen))
        assertEquals(ComputerUseFailure.RegionOutOfBounds, assertIs<InputOutcome.Rejected>(outside).reason)
    }

    @Test
    fun `window input is refused when the window cannot be activated`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, ComputerUseMode.Window(windowTarget()))
        fixture.windows.isActivationAllowed = false
        val outcome = fixture.coordinator.input(InputAction.Click(FramePoint(1.0, 1.0)))
        assertEquals(ComputerUseFailure.ActivationFailed, assertIs<InputOutcome.Rejected>(outcome).reason)
        assertTrue(fixture.injector.applied.isEmpty())
    }

    @Test
    fun `window input activates the window first`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, ComputerUseMode.Window(windowTarget()))
        fixture.coordinator.capture(CaptureRequest())
        assertEquals(InputOutcome.Applied, fixture.coordinator.input(InputAction.Click(FramePoint(1.0, 1.0))))
        assertEquals(1, fixture.windows.activations)
    }

    @Test
    fun `retina master coordinates map to the logical host window`() = runTest {
        val fixture = fixture(400, 200)
        fixture.capturer.bounds = io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds(30, 40, 200, 100)
        fixture.coordinator.open(Session, Desktop)
        fixture.coordinator.capture(CaptureRequest())
        assertEquals(
            InputOutcome.Applied,
            fixture.coordinator.input(InputAction.Click(FramePoint(300.0, 100.0), space = FrameSpace.Master)),
        )
        assertEquals(ScreenPoint(180, 90), fixture.injector.points.single())
    }

    @Test
    fun `cropped previews retain their master region when mapping input`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        val frame = assertIs<CaptureOutcome.Produced>(
            fixture.coordinator.capture(CaptureRequest(region = CaptureRegion(80, 30, 40, 20))),
        ).result
        val preview = assertNotNull(frame.reference)
        assertEquals(InputOutcome.Applied, fixture.coordinator.input(InputAction.Click(FramePoint(20.0, 10.0))))
        assertEquals(ScreenPoint(110, 60), fixture.injector.points.single())
        val zoom = assertIs<CaptureOutcome.Produced>(
            fixture.coordinator.crop(CropRequest(preview.id, region = CaptureRegion(100, 40, 20, 10))),
        ).result
        assertNotNull(zoom.reference)
        assertEquals(InputOutcome.Applied, fixture.coordinator.input(InputAction.Click(FramePoint(5.0, 5.0))))
        assertEquals(ScreenPoint(115, 65), fixture.injector.points.last())
    }

    @Test
    fun `switching sessions and closing the profile deletes every owned frame`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        val first = assertNotNull(
            assertIs<CaptureOutcome.Produced>(fixture.coordinator.capture(CaptureRequest())).result.master,
        )
        fixture.coordinator.open(CaptureSessionId("next"), Desktop)
        assertTrue(fixture.store.files.keys.none { it.startsWith(Session.value) })
        val result = fixture.coordinator.crop(CropRequest(first.id))
        assertEquals(ComputerUseFailure.UnknownCapture, assertIs<CaptureOutcome.Rejected>(result).reason)
        fixture.coordinator.capture(CaptureRequest())
        fixture.coordinator.closeAndPurge()
        assertTrue(fixture.store.files.isEmpty())
        assertEquals(
            ComputerUseFailure.Unavailable,
            assertIs<CaptureOutcome.Rejected>(fixture.coordinator.capture(CaptureRequest())).reason,
        )
    }

    @Test
    fun `fractional negative and nonfinite coordinates are refused before injection`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, Desktop)
        fixture.coordinator.capture(CaptureRequest())
        listOf(FramePoint(-0.1, 0.0), FramePoint(Double.NaN, 0.0), FramePoint(Double.POSITIVE_INFINITY, 0.0)).forEach {
            assertIs<InputOutcome.Rejected>(fixture.coordinator.input(InputAction.Click(it)))
        }
        assertTrue(fixture.injector.applied.isEmpty())
    }

    private class Fixture(
        val capturer: FakeScreenCapturer,
        val windows: FakeWindowCatalog,
        val injector: FakeInputInjector,
        val store: FakeFrameStore,
        val coordinator: CaptureCoordinator,
        val smallJpeg: CaptureEncoding,
    )

    private fun TestScope.fixture(widthPx: Int = 200, heightPx: Int = 100): Fixture {
        val capturer = FakeScreenCapturer(widthPx, heightPx)
        val windows = FakeWindowCatalog(listOf(windowTarget(widthPx = widthPx, heightPx = heightPx)))
        val injector = FakeInputInjector()
        val store = FakeFrameStore()
        val encoder = FakeFrameEncoder()
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val pipeline = FramePipeline(encoder, store, VisionBudget(maxTokens = 100_000), dispatchers)
        val cache = MasterFrameCache(encoder, store, maxBytes = 64L * 1024 * 1024)
        val coordinator = CaptureCoordinator(capturer, windows, injector, pipeline, cache, dispatchers)
        return Fixture(
            capturer = capturer,
            windows = windows,
            injector = injector,
            store = store,
            coordinator = coordinator,
            smallJpeg = CaptureEncoding(
                format = CaptureFormat.Jpeg,
                quality = 80,
                maxWidthPx = 100,
                maxHeightPx = 100,
            ),
        )
    }

    private companion object {
        val Session = CaptureSessionId("session")
        val Desktop = ComputerUseMode.Desktop()
    }
}
