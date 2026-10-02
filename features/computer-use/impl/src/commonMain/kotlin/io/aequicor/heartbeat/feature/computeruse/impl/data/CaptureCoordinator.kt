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
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseInputActivity
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
@Suppress("TooManyFunctions") // One coordinator serializes all operations on the single active capture.
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
                capturer.failure(mode)?.let { return@withLock it }
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
            runLogged("window enumeration") { windows.list() }.orEmpty().filterNot { it.isSelfOwned }
        }
    }

    /** Explicit lookup preserves the distinction between a host-owned target and a closed target. */
    suspend fun resolveTarget(id: io.aequicor.heartbeat.feature.computeruse.api.WindowId): WindowTarget? =
        withContext(dispatchers.io) { windows.resolve(id) }

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
        onProgress: suspend (CaptureSessionId, ComputerUseInputActivity) -> Unit = { _, _ -> },
    ): InputOutcome = withContext(dispatchers.io) {
        operations.withLock {
            val failure = guard()
            if (failure != null) {
                InputOutcome.Rejected(failure)
            } else {
                applyInput(action, expectedCapture, isFrameBound, guard, onProgress)
            }
        }
    }

    private suspend fun applyInput(
        action: InputAction,
        expectedCapture: CaptureId?,
        isFrameBound: Boolean,
        guard: suspend () -> ComputerUseFailure?,
        onProgress: suspend (CaptureSessionId, ComputerUseInputActivity) -> Unit,
    ): InputOutcome {
        val session = active ?: return InputOutcome.Rejected(ComputerUseFailure.Unavailable)
        val failure = inputFailure(session, expectedCapture, isFrameBound, guard)
        if (failure != null) return InputOutcome.Rejected(failure)
        val bounds = capturer.currentBounds(session.mode)
            ?: return InputOutcome.Rejected(ComputerUseFailure.TargetClosed)
        val capturedBounds = session.previewBounds
        if (capturedBounds != null && !bounds.hasSameSize(capturedBounds)) {
            return InputOutcome.Rejected(ComputerUseFailure.TargetResized)
        }
        val frames = mutex.withLock {
            session.bounds = bounds
            Frames(session.masterReference, session.preview)
        }
        if (!injector.isAvailable) return InputOutcome.Rejected(ComputerUseFailure.Unavailable)
        val map: (FramePoint) -> ScreenPoint? = { point -> mapPoint(point, action.space, bounds, frames) }
        var hasStarted = false
        val outcome = guard()?.let(InputOutcome::Rejected)
            ?: runLogged("input injection") {
                injector.applyObserved(action, map) { point ->
                    val activity = recordProgress(session, bounds, point, isFirst = !hasStarted)
                    hasStarted = true
                    onProgress(session.session, activity)
                }
            }
            ?: InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        log.i {
            "input session=${session.session} outcome=${outcome::class.simpleName.orEmpty()} " +
                "reason=${(outcome as? InputOutcome.Rejected)?.reason?.name.orEmpty()} action=${action::class.simpleName.orEmpty()}"
        }
        return outcome
    }

    private suspend fun recordProgress(
        session: ActiveCapture,
        authorizedBounds: ScreenBounds,
        point: ScreenPoint?,
        isFirst: Boolean,
    ): ComputerUseInputActivity {
        val current = capturer.currentBounds(session.mode)
        val isValidSize = current?.hasSameSize(authorizedBounds) == true
        val isForeground = (session.mode as? ComputerUseMode.Window)?.let {
            windows.isForeground(it.target)
        } == true
        return mutex.withLock {
            val previous = session.inputActivity
            val local = if (current != null && isValidSize && point != null) {
                FramePoint((point.x - current.x).toDouble(), (point.y - current.y).toDouble())
            } else {
                null
            }
            val pointer = if (isValidSize) local ?: previous.pointer else null
            previous.copy(
                bounds = current ?: previous.bounds,
                pointer = pointer,
                isPointerVisible = isForeground && pointer != null,
                sequence = previous.sequence + if (isFirst) 1 else 0,
                revision = previous.revision + 1,
            ).also { session.inputActivity = it }
        }
    }

    private suspend fun inputFailure(
        session: ActiveCapture,
        expectedCapture: CaptureId?,
        isFrameBound: Boolean,
        guard: suspend () -> ComputerUseFailure?,
    ): ComputerUseFailure? {
        if ((isFrameBound || expectedCapture != null) && session.preview?.id != expectedCapture) {
            return ComputerUseFailure.StaleFrame
        }
        return capturer.failure(
            session.mode,
        ) ?: activationFailure(session.mode) ?: guard() ?: capturer.failure(session.mode)
    }

    private suspend fun activationFailure(mode: ComputerUseMode): ComputerUseFailure? {
        if (mode !is ComputerUseMode.Window) return null
        val outcome = runLogged("window activation") {
            windows.activationFailure(mode.target, mode.isClientAreaOnly)?.let(InputOutcome::Rejected)
                ?: InputOutcome.Applied
        } ?: InputOutcome.Rejected(ComputerUseFailure.ActivationFailed)
        return (outcome as? InputOutcome.Rejected)?.reason
    }

    private suspend fun masterFrame(
        session: ActiveCapture,
        isFresh: Boolean,
        isCursorIncluded: Boolean,
    ): StoredMaster? {
        val cached = if (isFresh) null else cachedMaster(session)
        return cached ?: captureMaster(session, isCursorIncluded)
    }

    /**
     * Observes geometry without activating the target or waiting behind a long input action. Snapshot/revision
     * fencing prevents a slow native observation from overwriting a newer movement or replacement session.
     */
    suspend fun observeActivity(sessionId: CaptureSessionId): ComputerUseInputActivity? = withContext(dispatchers.io) {
        val snapshot = mutex.withLock {
            active?.takeIf { it.session == sessionId }?.let { it to it.inputActivity }
        } ?: return@withContext null
        val (session, previous) = snapshot
        val bounds = capturer.currentBounds(session.mode)
        val hasSizeChanged = bounds != null && previous.bounds?.let { !bounds.hasSameSize(it) } == true
        val point = if (hasSizeChanged) null else previous.pointer
        val window = session.mode as? ComputerUseMode.Window
        val isVisible = bounds != null && point != null && window != null && windows.isForeground(window.target)
        val next = previous.copy(bounds = bounds ?: previous.bounds, pointer = point, isPointerVisible = isVisible)
        mutex.withLock {
            if (active !== session) return@withLock null
            if (session.inputActivity.revision == previous.revision && next != previous) {
                session.inputActivity = next.copy(revision = previous.revision + 1)
            }
            session.inputActivity
        }
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
            ) { capturer.capture(session.mode, null, isCursorIncluded && session.mode is ComputerUseMode.Desktop) }
        }
        val pixels = raw?.let { frame ->
            if (session.mode is ComputerUseMode.Window && isCursorIncluded) {
                val activity = mutex.withLock { session.inputActivity }
                val pointer = activity.pointer.takeIf { activity.bounds?.hasSameSize(frame.bounds) == true }
                capturer.markPointer(frame, pointer).pixels
            } else {
                frame.pixels
            }
        }
        val stored = pixels?.let { storeMaster(session, it) }
        if (pixels == null || stored == null) return null
        cache.put(stored.reference, pixels, raw.bounds)
        mutex.withLock {
            session.masterReference = stored.reference
            session.bounds = raw.bounds
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
        capturer.failure(session.mode)?.let { return CaptureOutcome.Rejected(it) }
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
        var inputActivity: ComputerUseInputActivity = ComputerUseInputActivity(),
    )

    private data class StoredMaster(val reference: CaptureRef, val pixels: PixelGrid)

    private data class Frames(val master: CaptureRef?, val preview: CaptureRef?)

    private companion object {
        fun ComputerUseMode.label(): String = this::class.simpleName ?: "Mode"
    }
}
