package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureClip
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ClipFrame
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseGesture
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseInputActivity
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.NativeCapture
import io.aequicor.heartbeat.feature.computeruse.api.RecordRequest
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.TileGrid
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.api.fitsRoi
import io.aequicor.heartbeat.feature.computeruse.api.roiIn
import io.aequicor.heartbeat.feature.computeruse.impl.domain.CaptureRegionPolicy
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_CLICKS
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenCapturer
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.WindowCatalog
import io.aequicor.heartbeat.feature.computeruse.impl.domain.autoRoi
import io.aequicor.heartbeat.feature.computeruse.impl.domain.scaledInto
import io.aequicor.heartbeat.feature.computeruse.impl.domain.wheelNotches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Outcome of one recording. */
internal sealed interface ClipOutcome {
    /** The keyframes were stored. */
    data class Recorded(val clip: CaptureClip) : ClipOutcome

    /** The recording was refused or a frame could not be captured. */
    data class Rejected(val reason: ComputerUseFailure) : ClipOutcome
}

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
 *
 * The active ROI (rectangle of interest) comes with every operation from the machine: frames default to it, tiles
 * are laid over it, and frames, crops or input points leaving it are refused with [ComputerUseFailure.OutsideRoi].
 * An ROI that no longer fits the master frame (the target was resized) is ignored.
 */
// One coordinator serializes all operations on the single active capture, including recordings: it owns every port.
@Suppress("TooManyFunctions", "LongParameterList")
internal class CaptureCoordinator(
    private val capturer: ScreenCapturer,
    private val windows: WindowCatalog,
    private val injector: InputInjector,
    private val pipeline: FramePipeline,
    private val cache: MasterFrameCache,
    private val dispatchers: DispatcherProvider,
    private val recorder: ClipRecorder,
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
    suspend fun capture(request: CaptureRequest, roi: CaptureRegion? = null): CaptureOutcome =
        withContext(dispatchers.io) {
            operations.withLock { captureLocked(request, roi) }
        }

    private suspend fun captureLocked(request: CaptureRequest, roi: CaptureRegion?): CaptureOutcome {
        val session = active ?: return CaptureOutcome.Rejected(ComputerUseFailure.Unavailable)
        val master = masterFrame(session, request.isFresh, request.isCursorIncluded) ?: return rejected(session)
        val activeRoi = roi.within(master.pixels)
        val region = requestedRegion(request, master.pixels, activeRoi)
            ?: return CaptureOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
        if (!region.fitsRoi(activeRoi)) return CaptureOutcome.Rejected(ComputerUseFailure.OutsideRoi)
        return produceFrame(session, master, region, request, activeRoi)
    }

    private suspend fun produceFrame(
        session: ActiveCapture,
        master: StoredMaster,
        region: CaptureRegion,
        request: CaptureRequest,
        activeRoi: CaptureRegion?,
    ): CaptureOutcome {
        val produced = pipeline.derive(
            session = session.session,
            id = CaptureId(Uuid.random().toString()),
            source = master.pixels.region(region),
            roi = region,
            masterWidthPx = master.reference.masterWidthPx,
            masterHeightPx = master.reference.masterHeightPx,
            encoding = request.encoding,
            sequence = nextSequence(),
        ) ?: return CaptureOutcome.Rejected(ComputerUseFailure.EncodingTooLarge)
        cache.alias(produced.reference.id, master.reference.id)
        mutex.withLock {
            session.preview = produced.reference
            session.previewBounds = cache.bounds(master.reference.id)
        }
        return CaptureOutcome.Produced(
            CaptureResult(
                reference = produced.reference,
                master = master.reference,
                tiles = tilesFor(master.reference, activeRoi),
            ),
        )
    }

    /**
     * Records keyframes inside the active [roi] and frames them to the area that changed (auto-ROI). The last frame
     * becomes the master and the frame input coordinates refer to; earlier keyframes are cut from bounded working
     * copies and alias that master. Frames are captured without the cursor, so only the application moves.
     */
    suspend fun record(request: RecordRequest, roi: CaptureRegion? = null): ClipOutcome = withContext(dispatchers.io) {
        operations.withLock {
            val session = active ?: return@withLock ClipOutcome.Rejected(ComputerUseFailure.Unavailable)
            val recorded = recorder.record(
                request,
                area = { pixels -> roi.within(pixels) ?: CaptureRegion(0, 0, pixels.widthPx, pixels.heightPx) },
                capture = {
                    runLogged("recording frame") { capturer.capture(session.mode, null, isCursorIncluded = false) }
                },
            ) ?: return@withLock ClipOutcome.Rejected(rejected(session).reason)
            storeClip(session, request, recorded, roi)
        }
    }

    private suspend fun storeClip(
        session: ActiveCapture,
        request: RecordRequest,
        recorded: RecordedFrames,
        roi: CaptureRegion?,
    ): ClipOutcome {
        val full = recorded.last.pixels
        val master = storeMaster(session, full) ?: return ClipOutcome.Rejected(ComputerUseFailure.CaptureFailed)
        val area = roi.within(full) ?: CaptureRegion(0, 0, full.widthPx, full.heightPx)
        val framed = autoRoi(recorded.changed, area)
        val sources = recorded.keyframes.dropLast(
            1,
        ).map { it to it.frame.region(framed.scaledInto(recorded.scale, it.frame)) } +
            (recorded.keyframes.last().copy(offsetMillis = recorded.durationMillis) to full.region(framed))
        val encoding = request.encoding.copy(
            maxBytes = request.encoding.maxBytes.takeIf { it > 0 }?.let { it / sources.size } ?: 0,
        )
        val frames = sources.map { (keyframe, pixels) ->
            val produced = pipeline.derive(
                session = session.session,
                id = CaptureId(Uuid.random().toString()),
                source = pixels,
                roi = framed,
                masterWidthPx = full.widthPx,
                masterHeightPx = full.heightPx,
                encoding = encoding,
                sequence = nextSequence(),
            ) ?: return ClipOutcome.Rejected(ComputerUseFailure.EncodingTooLarge)
            ClipFrame(produced.reference, keyframe.offsetMillis, keyframe.changedFraction)
        }
        // Only the last keyframe shows the master; earlier ones are cut from working copies and cannot be zoomed.
        cache.put(master.reference, full, recorded.last.bounds)
        cache.alias(frames.last().capture.id, master.reference.id)
        mutex.withLock {
            session.masterReference = master.reference
            session.bounds = recorded.last.bounds
            session.preview = frames.last().capture
            session.previewBounds = recorded.last.bounds
            session.frameCount += frames.size
        }
        log.i {
            "clip recorded session=${session.session} frames=${frames.size} duration=${recorded.durationMillis} " +
                "stable=${recorded.isStable} auto=${framed != area}"
        }
        return ClipOutcome.Recorded(
            CaptureClip(frames, framed, framed != area, recorded.isStable, recorded.durationMillis, master.reference),
        )
    }

    /** Cuts a region out of an already stored master frame, optionally upscaled, inside the active [roi]. */
    suspend fun crop(request: CropRequest, roi: CaptureRegion? = null): CaptureOutcome = withContext(dispatchers.io) {
        operations.withLock {
            val session = active ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.Unavailable)
            val master = cache.reference(request.capture)
                ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.UnknownCapture)
            if (master.session != session.session) {
                return@withLock CaptureOutcome.Rejected(ComputerUseFailure.UnknownCapture)
            }
            val activeRoi = roi?.takeIf {
                it.isInside(
                    CaptureRegion(0, 0, master.masterWidthPx, master.masterHeightPx),
                )
            }
            val region = request.roiIn(master, activeRoi)
                ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
            if (!region.fitsRoi(activeRoi)) return@withLock CaptureOutcome.Rejected(ComputerUseFailure.OutsideRoi)
            val pixels = cache.pixels(master.id)
                ?: return@withLock CaptureOutcome.Rejected(ComputerUseFailure.CaptureExpired)
            val produced = pipeline.derive(
                session = session.session,
                id = CaptureId(Uuid.random().toString()),
                source = upscale(pixels.region(region), request.scale),
                roi = region,
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

    /** Applies one input action inside the captured area and its active [roi]. */
    // The input contract carries its frame binding, ROI, live guard and feedback sink together.
    @Suppress("LongParameterList")
    suspend fun input(
        action: InputAction,
        expectedCapture: CaptureId? = null,
        isFrameBound: Boolean = false,
        roi: CaptureRegion? = null,
        guard: suspend () -> ComputerUseFailure? = { null },
        onProgress: suspend (CaptureSessionId, ComputerUseInputActivity) -> Unit = { _, _ -> },
    ): InputOutcome = withContext(dispatchers.io) {
        operations.withLock {
            val failure = guard()
            if (failure != null) {
                InputOutcome.Rejected(failure)
            } else {
                applyInput(action, InputBinding(expectedCapture, isFrameBound, roi), guard, onProgress)
            }
        }
    }

    private suspend fun applyInput(
        action: InputAction,
        binding: InputBinding,
        guard: suspend () -> ComputerUseFailure?,
        onProgress: suspend (CaptureSessionId, ComputerUseInputActivity) -> Unit,
    ): InputOutcome {
        val session = active ?: return InputOutcome.Rejected(ComputerUseFailure.Unavailable)
        val failure = inputFailure(session, binding.expectedCapture, binding.isFrameBound, guard)
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
        if (frames.leavesRoi(action, bounds, binding.roi)) return InputOutcome.Rejected(ComputerUseFailure.OutsideRoi)
        val map: (FramePoint) -> ScreenPoint? = { point -> mapPoint(point, action.space, bounds, frames) }
        val feedback = InputFeedback { step -> onProgress(session.session, recordProgress(session, bounds, step)) }
        val outcome = guard()?.let(InputOutcome::Rejected)
            ?: runLogged("input injection") {
                injector.applyObserved(
                    action,
                    map,
                    onProgress = { point -> feedback.report(point, isEffect = false) },
                    onEffect = {
                        val refusal = arrivalFailure(session.mode, bounds, guard)
                        if (refusal == null) feedback.effect(action.gesture())
                        refusal
                    },
                    area = (frames.preview ?: frames.master)?.let { bounds.narrowedTo(binding.roi, it) },
                )
            }
            ?: InputOutcome.Rejected(ComputerUseFailure.InputRejected)
        feedback.release()
        log.i {
            "input session=${session.session} outcome=${outcome::class.simpleName.orEmpty()} " +
                "reason=${(outcome as? InputOutcome.Rejected)?.reason?.name.orEmpty()} action=${action::class.simpleName.orEmpty()}"
        }
        return outcome
    }

    @HighFrequency
    private suspend fun recordProgress(
        session: ActiveCapture,
        authorizedBounds: ScreenBounds,
        step: ProgressStep,
    ): ComputerUseInputActivity {
        val current = capturer.currentBounds(session.mode)
        val isValidSize = current?.hasSameSize(authorizedBounds) == true
        val isShown = isCursorShown(session.mode)
        return mutex.withLock {
            val previous = session.inputActivity
            val point = step.point
            val local = if (current != null && isValidSize && point != null) {
                FramePoint((point.x - current.x).toDouble(), (point.y - current.y).toDouble())
            } else {
                null
            }
            val pointer = if (isValidSize) local ?: previous.pointer else null
            previous.copy(
                bounds = current ?: previous.bounds,
                pointer = pointer,
                isPointerVisible = isShown && pointer != null,
                sequence = previous.sequence + if (step.isFirst) 1 else 0,
                revision = previous.revision + 1,
                gesture = step.gesture,
                effectSequence = previous.effectSequence + if (step.isEffect) 1 else 0,
            ).also { session.inputActivity = it }
        }
    }

    /** The virtual cursor shows on the desktop always, over a window only while it is in the foreground. */
    @HighFrequency
    private suspend fun isCursorShown(mode: ComputerUseMode): Boolean = when (mode) {
        is ComputerUseMode.Desktop -> true
        is ComputerUseMode.Window -> windows.isForeground(mode.target)
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

    /**
     * Repeats the checks once the pointer glided to the target: focus, permissions or the target itself may have
     * changed, and the target must still be where the action's points were mapped, or the effect would land elsewhere.
     */
    private suspend fun arrivalFailure(
        mode: ComputerUseMode,
        authorized: ScreenBounds,
        guard: suspend () -> ComputerUseFailure?,
    ): ComputerUseFailure? {
        val current = capturer.currentBounds(mode)
        val placement = when {
            current == null -> ComputerUseFailure.TargetClosed

            current.x != authorized.x || current.y != authorized.y || !current.hasSameSize(authorized) ->
                ComputerUseFailure.TargetResized

            else -> null
        }
        return guard() ?: capturer.failure(mode) ?: placement ?: activationFailure(mode)
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
        val isVisible = bounds != null && point != null && isCursorShown(session.mode)
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
        roi = CaptureRegion(0, 0, pixels.widthPx, pixels.heightPx),
        sequence = nextSequence(),
    )

    private suspend fun rejected(session: ActiveCapture): CaptureOutcome.Rejected {
        capturer.failure(session.mode)?.let { return CaptureOutcome.Rejected(it) }
        val stillThere = runLogged("bounds probe") { capturer.currentBounds(session.mode) }
        return CaptureOutcome.Rejected(
            if (stillThere == null) ComputerUseFailure.TargetClosed else ComputerUseFailure.CaptureFailed,
        )
    }

    /** A tile is laid over the active ROI; without an explicit ROI or tile the frame shows the active ROI. */
    private fun requestedRegion(request: CaptureRequest, master: PixelGrid, activeRoi: CaptureRegion?): CaptureRegion? {
        val full = CaptureRegion(0, 0, master.widthPx, master.heightPx)
        val base = activeRoi ?: full
        val tile = request.tile
        if (tile != null) return base.tile(tile.column, tile.row)
        return request.roi?.takeIf { it.isInside(full) } ?: if (request.roi == null) base else null
    }

    private fun tilesFor(master: CaptureRef, activeRoi: CaptureRegion?): TileGrid? {
        val grid = TileGrid.forMaster(
            activeRoi?.widthPx ?: master.masterWidthPx,
            activeRoi?.heightPx ?: master.masterHeightPx,
        )
        return if (grid.columns * grid.rows > 1) grid else null
    }

    /** `true` when a pointer point of [action] maps into the master outside the active [roi]. */
    private fun Frames.leavesRoi(action: InputAction, bounds: ScreenBounds, roi: CaptureRegion?): Boolean {
        if (roi == null) return false
        return action.points().any { point ->
            val inMaster = masterOf(point, action.space, bounds, this)
            inMaster != null && (inMaster.x !in roi.x until roi.right || inMaster.y !in roi.y until roi.bottom)
        }
    }

    private fun masterOf(point: FramePoint, space: FrameSpace?, bounds: ScreenBounds, frames: Frames): ScreenPoint? {
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        val master = frames.master ?: return null
        val preview = frames.preview ?: master
        if (space == FrameSpace.Screen) {
            val screen = screenPoint(point, bounds) ?: return null
            return ScreenPoint(
                ((screen.x - bounds.x).toDouble() * preview.masterWidthPx / bounds.widthPx).toInt(),
                ((screen.y - bounds.y).toDouble() * preview.masterHeightPx / bounds.heightPx).toInt(),
            )
        }
        return masterPoint(point, space, preview, preview)
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
        val sourceMaster = frames.preview ?: master
        val inMaster = masterOf(point, space, bounds, frames) ?: return null
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
            preview.roi.x + (point.x * preview.roi.widthPx / preview.widthPx).toInt(),
            preview.roi.y + (point.y * preview.roi.heightPx / preview.heightPx).toInt(),
        )
    }

    /** Imports an engine frame into this session, then applies the same preview budget, ROI and crop cache. */
    suspend fun importNative(
        capture: NativeCapture,
        request: CaptureRequest,
        roi: CaptureRegion? = null,
    ): CaptureResult = withContext(dispatchers.io) {
        operations.withLock {
            val session = active ?: return@withLock CaptureResult(failure = ComputerUseFailure.Unavailable)
            val bounds = capturer.currentBounds(session.mode)
                ?: return@withLock CaptureResult(failure = ComputerUseFailure.TargetClosed)
            val pixels = pipeline.decode(capture.content)
                ?: return@withLock CaptureResult(failure = ComputerUseFailure.CaptureFailed)
            val stored = storeMaster(session, pixels)
                ?: return@withLock CaptureResult(failure = ComputerUseFailure.CaptureFailed)
            cache.put(stored.reference, pixels, bounds)
            val activeRoi = roi.within(pixels)
            val region = requestedRegion(request, pixels, activeRoi)
                ?: return@withLock CaptureResult(failure = ComputerUseFailure.RegionOutOfBounds)
            if (!region.fitsRoi(activeRoi)) return@withLock CaptureResult(failure = ComputerUseFailure.OutsideRoi)
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
                tiles = tilesFor(stored.reference, activeRoi),
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

    private data class InputBinding(val expectedCapture: CaptureId?, val isFrameBound: Boolean, val roi: CaptureRegion?)

    private companion object {
        fun ComputerUseMode.label(): String = this::class.simpleName ?: "Mode"

        /** The ROI when it still fits [master]; a resized target makes it stale, and it is ignored. */
        fun CaptureRegion?.within(master: PixelGrid): CaptureRegion? =
            this?.takeIf { it.isInside(CaptureRegion(0, 0, master.widthPx, master.heightPx)) }

        /** Tile `column:row` of the grid laid over this rectangle, in the rectangle's own coordinate space. */
        fun CaptureRegion.tile(column: Int, row: Int): CaptureRegion? =
            TileGrid.forMaster(widthPx, heightPx).region(column, row, widthPx, heightPx)?.let { tile ->
                CaptureRegion(x + tile.x, y + tile.y, tile.widthPx, tile.heightPx)
            }

        /** What the virtual cursor shows once this action's effect starts; typed text is reduced to its length. */
        fun InputAction.gesture(): ComputerUseGesture = when (this) {
            is InputAction.MoveTo -> ComputerUseGesture.Move
            is InputAction.Click -> ComputerUseGesture.Press(button, count.coerceIn(1, MAX_CLICKS))
            is InputAction.Drag -> ComputerUseGesture.Drag(button)
            is InputAction.Scroll -> ComputerUseGesture.Scroll(wheelNotches(deltaY))
            is InputAction.Type -> ComputerUseGesture.Type(text.length)
            is InputAction.Key -> ComputerUseGesture.Keys(keys)
        }

        fun InputAction.points(): List<FramePoint> = when (this) {
            is InputAction.MoveTo -> listOf(point)
            is InputAction.Click -> listOf(point)
            is InputAction.Drag -> listOf(from, to)
            is InputAction.Scroll -> listOf(point)
            is InputAction.Type, is InputAction.Key -> emptyList()
        }
    }
}

/** The screen rectangle input may cross: these bounds narrowed to the active [roi] of [source]; `null` when empty. */
private fun ScreenBounds.narrowedTo(roi: CaptureRegion?, source: CaptureRef): ScreenBounds? {
    if (roi == null) return this
    val left = x + ceilDiv(roi.x.toLong() * widthPx, source.masterWidthPx)
    val top = y + ceilDiv(roi.y.toLong() * heightPx, source.masterHeightPx)
    val right = x + (roi.right.toLong() * widthPx / source.masterWidthPx).toInt()
    val bottom = y + (roi.bottom.toLong() * heightPx / source.masterHeightPx).toInt()
    return if (right > left && bottom > top) ScreenBounds(left, top, right - left, bottom - top) else null
}

private fun ceilDiv(value: Long, divisor: Int): Int = ((value + divisor - 1) / divisor).toInt()

private data class ProgressStep(
    val point: ScreenPoint?,
    val isFirst: Boolean,
    val gesture: ComputerUseGesture,
    val isEffect: Boolean,
)

/** Cursor feedback of one input action: its steps, the gesture of its effect and the release of a drag. */
private class InputFeedback(private val sink: suspend (ProgressStep) -> Unit) {
    private var gesture: ComputerUseGesture = ComputerUseGesture.Move
    private var hasStarted = false

    suspend fun report(point: ScreenPoint?, isEffect: Boolean) {
        val step = ProgressStep(point, isFirst = !hasStarted, gesture = gesture, isEffect = isEffect)
        hasStarted = true
        sink(step)
    }

    /** The effect of [effectGesture] reaches the driver now. */
    suspend fun effect(effectGesture: ComputerUseGesture) {
        gesture = effectGesture
        report(null, isEffect = true)
    }

    /** A finished drag released its button: the cursor stops showing it held, and its trail fades. */
    suspend fun release() {
        if (gesture !is ComputerUseGesture.Drag) return
        gesture = ComputerUseGesture.Move
        report(null, isEffect = false)
    }
}
