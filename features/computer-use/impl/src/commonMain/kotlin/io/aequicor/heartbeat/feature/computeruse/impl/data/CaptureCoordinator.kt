package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.TileGrid
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.api.regionIn
import io.aequicor.heartbeat.feature.computeruse.impl.domain.CaptureRegionPolicy
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenCapturer
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.WindowCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Outcome of one coordinator operation; refusals are values, not exceptions. */
internal sealed interface CaptureOutcome {
    /** A frame was stored and can be read through its reference. */
    data class Produced(val result: CaptureResult) : CaptureOutcome

    /** The operation was refused; the session stays usable unless the reason says otherwise. */
    data class Rejected(val reason: ComputerUseFailure) : CaptureOutcome
}

/**
 * The single active capture of the profile.
 *
 * It owns the coordinate spaces: the master frame is captured in physical screen pixels, previews and crops are
 * derived from it, and every caller-supplied point is mapped back through the frame the caller received. Input
 * outside the captured area is refused instead of clamped, so an agent cannot click something it never saw.
 */
internal class CaptureCoordinator(
    private val capturer: ScreenCapturer,
    private val windows: WindowCatalog,
    private val injector: InputInjector,
    private val pipeline: FramePipeline,
    private val cache: MasterFrameCache,
    private val dispatchers: DispatcherProvider,
) {
    private val log = Log.tag("CaptureCoordinator")
    private val mutex = Mutex()
    private var active: ActiveCapture? = null
    private var lastSession: CaptureSessionId? = null
    private var sequence = 0L

    /** Opens a capture of [mode]; a previously open session keeps its frames until [purge]. */
    suspend fun open(session: CaptureSessionId, mode: ComputerUseMode): ComputerUseFailure? =
        withContext(dispatchers.io) {
            val bounds = capturer.currentBounds(mode) ?: return@withContext ComputerUseFailure.TargetClosed
            mutex.withLock {
                active = ActiveCapture(session, mode, bounds)
                log.i {
                    "capture opened session=$session mode=${mode.label()} area=${bounds.widthPx}x${bounds.heightPx}"
                }
            }
            null
        }

    /** Ends the active capture; stored frames survive until [purge]. */
    suspend fun close() {
        mutex.withLock {
            val ended = active ?: return@withLock
            active = null
            lastSession = ended.session
            log.i { "capture closed session=${ended.session} frames=${ended.frameCount}" }
        }
    }

    /** Deletes the stored master frames of the finished session. */
    suspend fun purge() {
        val session = mutex.withLock {
            val target = lastSession ?: active?.session
            lastSession = null
            target
        }
        if (session == null) {
            log.d { "nothing to purge" }
            return
        }
        cache.forgetSession(session)
    }

    /** The capturable windows of this host; empty when window capture is unavailable. */
    suspend fun targets(): List<WindowTarget> = withContext(dispatchers.io) {
        if (!windows.isAvailable) {
            emptyList()
        } else {
            runLogged("window enumeration") { windows.list() } ?: emptyList()
        }
    }

    /** The current screen rectangle of the captured area; `null` when nothing is captured. */
    suspend fun currentBounds(): ScreenBounds? = mutex.withLock { active?.bounds }

    /** Captures one frame: a fresh master when asked, otherwise the stored one, then the requested reduction. */
    suspend fun capture(request: CaptureRequest): CaptureOutcome = withContext(dispatchers.io) {
        val session = active ?: return@withContext CaptureOutcome.Rejected(ComputerUseFailure.Unavailable)
        val master = masterFrame(session, request.isFresh)
            ?: return@withContext rejected(session)
        val region = requestedRegion(request, master.pixels)
            ?: return@withContext CaptureOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
        val produced = pipeline.derive(
            session = session.session,
            id = CaptureId(Uuid.random().toString()),
            source = master.pixels.region(region),
            region = region,
            masterWidthPx = master.reference.masterWidthPx,
            masterHeightPx = master.reference.masterHeightPx,
            encoding = request.encoding,
            sequence = nextSequence(),
        ) ?: return@withContext CaptureOutcome.Rejected(ComputerUseFailure.EncodingTooLarge)
        mutex.withLock { session.preview = produced.reference }
        CaptureOutcome.Produced(
            CaptureResult(
                reference = produced.reference,
                master = master.reference,
                tiles = tilesFor(master.reference),
            ),
        )
    }

    /** Cuts a region out of an already stored master frame, optionally upscaled. */
    suspend fun crop(request: CropRequest): CaptureOutcome = withContext(dispatchers.io) {
        val session = active ?: return@withContext CaptureOutcome.Rejected(ComputerUseFailure.Unavailable)
        val master = cache.reference(request.capture)
            ?: return@withContext CaptureOutcome.Rejected(ComputerUseFailure.UnknownCapture)
        if (master.session != session.session) {
            return@withContext CaptureOutcome.Rejected(ComputerUseFailure.UnknownCapture)
        }
        val region = request.regionIn(master)
            ?: return@withContext CaptureOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
        val pixels = cache.pixels(request.capture)
            ?: return@withContext CaptureOutcome.Rejected(ComputerUseFailure.CaptureExpired)
        val produced = pipeline.derive(
            session = session.session,
            id = CaptureId(Uuid.random().toString()),
            source = upscale(pixels.region(region), request.scale),
            region = region,
            masterWidthPx = master.masterWidthPx,
            masterHeightPx = master.masterHeightPx,
            encoding = request.encoding,
            sequence = nextSequence(),
        ) ?: return@withContext CaptureOutcome.Rejected(ComputerUseFailure.EncodingTooLarge)
        mutex.withLock { session.crop = produced.reference }
        CaptureOutcome.Produced(CaptureResult(reference = produced.reference, master = master))
    }

    /** Applies one input action inside the captured area. */
    suspend fun input(action: InputAction): InputOutcome = withContext(dispatchers.io) {
        val session = active ?: return@withContext InputOutcome.Rejected(ComputerUseFailure.Unavailable)
        val bounds = capturer.currentBounds(session.mode)
            ?: return@withContext InputOutcome.Rejected(ComputerUseFailure.TargetClosed)
        val frames = mutex.withLock {
            session.bounds = bounds
            Frames(session.masterReference, session.preview)
        }
        val mode = session.mode
        if (mode is ComputerUseMode.Window && !activate(mode.target)) {
            return@withContext InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        }
        if (!injector.isAvailable) {
            return@withContext InputOutcome.Rejected(ComputerUseFailure.Unavailable)
        }
        val space = action.space
        val map: (FramePoint) -> ScreenPoint? = { point -> mapPoint(point, space, bounds, frames) }
        val outcome = runLogged("input injection") { injector.apply(action, map) }
            ?: InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        if (outcome is InputOutcome.Rejected) {
            log.w { "input refused reason=${outcome.reason} action=${action::class.simpleName}" }
        } else {
            log.i { "input applied action=${action::class.simpleName}" }
        }
        outcome
    }

    private suspend fun masterFrame(session: ActiveCapture, isFresh: Boolean): StoredMaster? {
        val cached = if (isFresh) null else cachedMaster(session)
        return cached ?: captureMaster(session)
    }

    /** The stored master frame of this session, re-decoded when its buffer was evicted. */
    private suspend fun cachedMaster(session: ActiveCapture): StoredMaster? {
        val known = mutex.withLock { session.masterReference }
        val reference = known?.let { cache.reference(it.id) }
        val pixels = known?.let { cache.pixels(it.id) }
        return if (reference != null && pixels != null) StoredMaster(reference, pixels) else null
    }

    /** Captures the whole area again and stores it as the master frame of this session. */
    private suspend fun captureMaster(session: ActiveCapture): StoredMaster? {
        val bounds = capturer.currentBounds(session.mode)
        val raw = if (bounds == null) null else runLogged("frame capture") { capturer.capture(session.mode, null) }
        val pixels = raw?.pixels
        val stored = pixels?.let { storeMaster(session, it) }
        if (pixels == null || stored == null) return null
        cache.put(stored.reference, pixels)
        mutex.withLock {
            session.masterReference = stored.reference
            session.bounds = bounds ?: session.bounds
            session.frameCount++
        }
        return StoredMaster(stored.reference, pixels)
    }

    private suspend fun storeMaster(session: ActiveCapture, pixels: PixelGrid): ProducedFrame? = pipeline.storeMaster(
        session = session.session,
        id = CaptureId(Uuid.random().toString()),
        source = pixels,
        region = CaptureRegion(0, 0, pixels.widthPx, pixels.heightPx),
        sequence = nextSequence(),
    )

    private suspend fun rejected(session: ActiveCapture): CaptureOutcome.Rejected {
        val stillThere = runLogged("bounds probe") { capturer.currentBounds(session.mode) }
        return CaptureOutcome.Rejected(
            if (stillThere == null) ComputerUseFailure.TargetClosed else ComputerUseFailure.CaptureFailed,
        )
    }

    private fun requestedRegion(request: CaptureRequest, master: PixelGrid): CaptureRegion? {
        val full = CaptureRegion(0, 0, master.widthPx, master.heightPx)
        val tile = request.tile
        if (tile != null) {
            val grid = TileGrid.forMaster(master.widthPx, master.heightPx)
            return grid.region(tile.column, tile.row, master.widthPx, master.heightPx)
        }
        return request.region?.intersect(full) ?: full
    }

    private fun tilesFor(master: CaptureRef): TileGrid? {
        val grid = TileGrid.forMaster(master.masterWidthPx, master.masterHeightPx)
        return if (grid.columns * grid.rows > 1) grid else null
    }

    private fun upscale(source: PixelGrid, scale: Double): PixelGrid {
        if (scale <= 1.0) return source
        val width = (source.widthPx * scale).toInt().coerceAtLeast(1)
        val height = (source.heightPx * scale).toInt().coerceAtLeast(1)
        return source.scaled(width, height).sharpened()
    }

    private fun mapPoint(point: FramePoint, space: FrameSpace?, bounds: ScreenBounds, frames: Frames): ScreenPoint? {
        if (space == FrameSpace.Screen) {
            val candidate = ScreenPoint(point.x.toInt(), point.y.toInt())
            val inside = candidate.x in bounds.x until bounds.right && candidate.y in bounds.y until bounds.bottom
            return if (inside) candidate else null
        }
        val master = frames.master ?: return null
        val previewWidth = frames.preview?.widthPx ?: master.masterWidthPx
        val previewHeight = frames.preview?.heightPx ?: master.masterHeightPx
        val inMaster = CaptureRegionPolicy.toMaster(
            point = point,
            space = space ?: FrameSpace.Preview,
            previewWidthPx = previewWidth,
            previewHeightPx = previewHeight,
            masterWidthPx = master.masterWidthPx,
            masterHeightPx = master.masterHeightPx,
        ) ?: return null
        return CaptureRegionPolicy.masterToScreen(inMaster, bounds)
    }

    private suspend fun activate(target: WindowTarget): Boolean =
        runLogged("window activation") { windows.activate(target) } ?: false

    private suspend fun nextSequence(): Long = mutex.withLock { sequence++ }

    private suspend fun <T> runLogged(operation: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "$operation failed" }
        null
    }

    private class ActiveCapture(
        val session: CaptureSessionId,
        val mode: ComputerUseMode,
        var bounds: ScreenBounds,
        var masterReference: CaptureRef? = null,
        var preview: CaptureRef? = null,
        var crop: CaptureRef? = null,
        var frameCount: Long = 0L,
    )

    private class StoredMaster(val reference: CaptureRef, val pixels: PixelGrid)

    private class Frames(val master: CaptureRef?, val preview: CaptureRef?)

    private companion object {
        fun ComputerUseMode.label(): String = this::class.simpleName ?: "Mode"
    }
}
