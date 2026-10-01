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
import io.aequicor.heartbeat.feature.computeruse.api.NativeCapture
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
import kotlinx.coroutines.NonCancellable
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
    private val operations = Mutex()
    private val sessions = mutableSetOf<CaptureSessionId>()
    private var active: ActiveCapture? = null
    private var lastSession: CaptureSessionId? = null
    private var sequence = 0L

    /** Opens a capture of [mode]; a previously open session keeps its frames until [purge]. */
    suspend fun open(session: CaptureSessionId, mode: ComputerUseMode): ComputerUseFailure? =
        withContext(dispatchers.io) {
            operations.withLock {
                if (mode is ComputerUseMode.Window &&
                    mode.isClientAreaOnly
                ) {
                    return@withLock ComputerUseFailure.ModeNotAllowed
                }
                val bounds = capturer.currentBounds(mode) ?: return@withLock ComputerUseFailure.TargetClosed
                val previous = active
                if (previous != null) cache.forgetSession(previous.session)
                sessions += session
                mutex.withLock {
                    active = ActiveCapture(session, mode, bounds)
                    log.i {
                        "capture opened session=$session mode=${mode.label()} area=${bounds.widthPx}x${bounds.heightPx}"
                    }
                }
                null
            }
        }

    /** Ends the active capture after its outstanding operations finish cancelling. */
    suspend fun close() = operations.withLock {
        mutex.withLock {
            val ended = active ?: return@withLock
            active = null
            lastSession = ended.session
            log.i { "capture closed session=${ended.session} frames=${ended.frameCount}" }
        }
    }

    /** Deletes the stored frames of the finished session. */
    suspend fun purge() = operations.withLock {
        val session = mutex.withLock {
            val target = lastSession ?: active?.session
            lastSession = null
            target
        }
        if (session != null) {
            cache.forgetSession(session)
            sessions.remove(session)
        }
    }

    /** Cleanup of an ended generation never closes or purges a newer active session. */
    suspend fun closeSessionAndPurge(session: CaptureSessionId?) = withContext(NonCancellable + dispatchers.io) {
        if (session == null) return@withContext
        operations.withLock {
            mutex.withLock {
                if (active?.session == session) active = null
                if (lastSession == session) lastSession = null
            }
            cache.forgetSession(session)
            sessions.remove(session)
        }
    }

    /** Profile-close barrier: cancel input first, then purge every session this coordinator owned. */
    suspend fun closeAndPurge() = withContext(NonCancellable + dispatchers.io) {
        operations.withLock {
            mutex.withLock {
                active = null
                lastSession = null
            }
            sessions.toList().forEach { cache.forgetSession(it) }
            sessions.clear()
            log.i { "all capture sessions purged" }
        }
    }

    /** The capturable windows of this host; empty when window capture is unavailable. */
    suspend fun targets(): List<WindowTarget> = withContext(dispatchers.io) {
        if (!windows.isAvailable) {
            emptyList()
        } else {
            runLogged("window enumeration") { windows.list() }.orEmpty()
        }
    }

    /** The current screen rectangle of the captured area; `null` when nothing is captured. */
    suspend fun currentBounds(): ScreenBounds? = mutex.withLock { active?.bounds }

    /** Captures one frame: a fresh master when asked, otherwise the stored one, then the requested reduction. */
    suspend fun capture(request: CaptureRequest): CaptureOutcome = withContext(dispatchers.io) {
        operations.withLock {
            val session = active ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.Unavailable)
            val master = masterFrame(session, request.isFresh, request.isCursorIncluded)
                ?: return@withLock rejected(session)
            val region = requestedRegion(request, master.pixels)
                ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
            val produced = pipeline.derive(
                session = session.session,
                id = CaptureId(Uuid.random().toString()),
                source = master.pixels.region(region),
                region = region,
                masterWidthPx = master.reference.masterWidthPx,
                masterHeightPx = master.reference.masterHeightPx,
                encoding = request.encoding,
                sequence = nextSequence(),
            ) ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.EncodingTooLarge)
            cache.alias(produced.reference.id, master.reference.id)
            mutex.withLock {
                session.preview = produced.reference
                session.previewBounds = cache.bounds(master.reference.id)
            }
            CaptureOutcome.Produced(
                CaptureResult(
                    reference = produced.reference,
                    master = master.reference,
                    tiles = tilesFor(master.reference),
                ),
            )
        }
    }

    /** Cuts a region out of an already stored master frame, optionally upscaled. */
    suspend fun crop(request: CropRequest): CaptureOutcome = withContext(dispatchers.io) {
        operations.withLock {
            val session = active ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.Unavailable)
            val master = cache.reference(request.capture)
                ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.UnknownCapture)
            if (master.session != session.session) {
                return@withLock CaptureOutcome.Rejected(ComputerUseFailure.UnknownCapture)
            }
            val region = request.regionIn(master)
                ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
            val pixels = cache.pixels(master.id)
                ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.CaptureExpired)
            val produced = pipeline.derive(
                session = session.session,
                id = CaptureId(Uuid.random().toString()),
                source = upscale(pixels.region(region), request.scale),
                region = region,
                masterWidthPx = master.masterWidthPx,
                masterHeightPx = master.masterHeightPx,
                encoding = request.encoding,
                sequence = nextSequence(),
            ) ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.EncodingTooLarge)
            cache.alias(produced.reference.id, master.id)
            mutex.withLock {
                session.crop = produced.reference
                session.preview = produced.reference
                session.previewBounds = cache.bounds(master.id)
            }
            CaptureOutcome.Produced(CaptureResult(reference = produced.reference, master = master))
        }
    }

    /** Applies one input action inside the captured area. */
    suspend fun input(
        action: InputAction,
        expectedCapture: CaptureId? = null,
        isFrameBound: Boolean = false,
        guard: suspend () -> ComputerUseFailure? = { null },
    ): InputOutcome = withContext(dispatchers.io) {
        operations.withLock {
            val failure = guard()
            if (failure != null) {
                InputOutcome.Rejected(failure)
            } else {
                applyInput(action, expectedCapture, isFrameBound, guard)
            }
        }
    }

    private suspend fun applyInput(
        action: InputAction,
        expectedCapture: CaptureId?,
        isFrameBound: Boolean,
        guard: suspend () -> ComputerUseFailure?,
    ): InputOutcome {
        val session = active ?: return InputOutcome.Rejected(ComputerUseFailure.Unavailable)
        if ((isFrameBound || expectedCapture != null) && session.preview?.id != expectedCapture) {
            return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        }
        val bounds = capturer.currentBounds(session.mode)
            ?: return InputOutcome.Rejected(ComputerUseFailure.TargetClosed)
        val capturedBounds = session.previewBounds
        if (capturedBounds != null && !bounds.hasSameSize(capturedBounds)) {
            return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        }
        val frames = mutex.withLock {
            session.bounds = bounds
            Frames(session.masterReference, session.preview)
        }
        val mode = session.mode
        if (mode is ComputerUseMode.Window && !activate(mode.target)) {
            return InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        }
        if (!injector.isAvailable) return InputOutcome.Rejected(ComputerUseFailure.Unavailable)
        val map: (FramePoint) -> ScreenPoint? = { point -> mapPoint(point, action.space, bounds, frames) }
        guard()?.let { return InputOutcome.Rejected(it) }
        val outcome = runLogged("input injection") { injector.apply(action, map) }
            ?: InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        log.i { "input outcome=${outcome::class.simpleName.orEmpty()} action=${action::class.simpleName.orEmpty()}" }
        return outcome
    }

    private suspend fun masterFrame(
        session: ActiveCapture,
        isFresh: Boolean,
        isCursorIncluded: Boolean,
    ): StoredMaster? {
        val cached = if (isFresh) null else cachedMaster(session)
        return cached ?: captureMaster(session, isCursorIncluded)
    }

    /** The stored master frame of this session, re-decoded when its buffer was evicted. */
    private suspend fun cachedMaster(session: ActiveCapture): StoredMaster? {
        val known = mutex.withLock { session.masterReference }
        val reference = known?.let { cache.reference(it.id) }
        val pixels = known?.let { cache.pixels(it.id) }
        return if (reference != null && pixels != null) StoredMaster(reference, pixels) else null
    }

    /** Captures the whole area again and stores it as the master frame of this session. */
    private suspend fun captureMaster(session: ActiveCapture, isCursorIncluded: Boolean): StoredMaster? {
        val bounds = capturer.currentBounds(session.mode)
        val raw = if (bounds == null) {
            null
        } else {
            runLogged(
                "frame capture",
            ) { capturer.capture(session.mode, null, isCursorIncluded) }
        }
        val pixels = raw?.pixels
        val stored = pixels?.let { storeMaster(session, it) }
        if (pixels == null || stored == null) return null
        cache.put(stored.reference, pixels, bounds)
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
        return request.region?.takeIf { it.isInside(full) } ?: if (request.region == null) full else null
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
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        if (space == FrameSpace.Screen) return screenPoint(point, bounds)
        val master = frames.master ?: return null
        val preview = frames.preview ?: master
        val sourceMaster = if (frames.preview != null) preview else master
        val inMaster = masterPoint(point, space, sourceMaster, preview) ?: return null
        val x = bounds.x + (inMaster.x.toDouble() * bounds.widthPx / sourceMaster.masterWidthPx).toInt()
        val y = bounds.y + (inMaster.y.toDouble() * bounds.heightPx / sourceMaster.masterHeightPx).toInt()
        return screenPoint(FramePoint(x.toDouble(), y.toDouble()), bounds)
    }

    private fun screenPoint(point: FramePoint, bounds: ScreenBounds): ScreenPoint? {
        val isHorizontalInside = point.x >= bounds.x && point.x < bounds.right
        val isVerticalInside = point.y >= bounds.y && point.y < bounds.bottom
        return if (isHorizontalInside && isVerticalInside) ScreenPoint(point.x.toInt(), point.y.toInt()) else null
    }

    private fun masterPoint(
        point: FramePoint,
        space: FrameSpace?,
        master: CaptureRef,
        preview: CaptureRef,
    ): ScreenPoint? {
        if (space != null && space != FrameSpace.Preview) {
            return CaptureRegionPolicy.toMaster(
                point,
                space,
                preview.widthPx,
                preview.heightPx,
                master.masterWidthPx,
                master.masterHeightPx,
            )
        }
        val isHorizontalInside = point.x >= 0 && point.x < preview.widthPx
        val isVerticalInside = point.y >= 0 && point.y < preview.heightPx
        if (!isHorizontalInside || !isVerticalInside) return null
        return ScreenPoint(
            preview.region.x + (point.x * preview.region.widthPx / preview.widthPx).toInt(),
            preview.region.y + (point.y * preview.region.heightPx / preview.heightPx).toInt(),
        )
    }

    /** Imports an engine frame into this session, then applies the same preview budget and crop cache. */
    suspend fun importNative(capture: NativeCapture, request: CaptureRequest): CaptureResult =
        withContext(dispatchers.io) {
            operations.withLock {
                val session = active ?: return@withLock CaptureResult(failure = ComputerUseFailure.Unavailable)
                val bounds = capturer.currentBounds(session.mode)
                    ?: return@withLock CaptureResult(failure = ComputerUseFailure.TargetClosed)
                val pixels = pipeline.decode(capture.content)
                    ?: return@withLock CaptureResult(failure = ComputerUseFailure.CaptureFailed)
                val stored = storeMaster(session, pixels)
                    ?: return@withLock CaptureResult(failure = ComputerUseFailure.CaptureFailed)
                cache.put(stored.reference, pixels, bounds)
                val region = requestedRegion(request, pixels)
                    ?: return@withLock CaptureResult(failure = ComputerUseFailure.RegionOutOfBounds)
                val produced = pipeline.derive(
                    session.session,
                    CaptureId(Uuid.random().toString()),
                    pixels.region(region),
                    region,
                    pixels.widthPx,
                    pixels.heightPx,
                    request.encoding,
                    nextSequence(),
                ) ?: return@withLock CaptureResult(failure = ComputerUseFailure.EncodingTooLarge)
                cache.alias(produced.reference.id, stored.reference.id)
                mutex.withLock {
                    session.masterReference = stored.reference
                    session.preview = produced.reference
                    session.previewBounds = bounds
                    session.bounds = bounds
                }
                CaptureResult(
                    reference = produced.reference,
                    master = stored.reference,
                    tiles = tilesFor(stored.reference),
                )
            }
        }

    private fun ScreenBounds.hasSameSize(other: ScreenBounds): Boolean =
        widthPx == other.widthPx && heightPx == other.heightPx

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

    // Mutable capture metadata is private to the serialized coordinator, with identity semantics.
    @Suppress("UseDataClass")
    private class ActiveCapture(
        val session: CaptureSessionId,
        val mode: ComputerUseMode,
        var bounds: ScreenBounds,
        var masterReference: CaptureRef? = null,
        var preview: CaptureRef? = null,
        var crop: CaptureRef? = null,
        var frameCount: Long = 0L,
        var previewBounds: ScreenBounds? = null,
    )

    private data class StoredMaster(val reference: CaptureRef, val pixels: PixelGrid)

    private data class Frames(val master: CaptureRef?, val preview: CaptureRef?)

    private companion object {
        fun ComputerUseMode.label(): String = this::class.simpleName ?: "Mode"
    }
}
