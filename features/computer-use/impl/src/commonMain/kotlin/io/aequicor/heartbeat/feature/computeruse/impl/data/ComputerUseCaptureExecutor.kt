package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureResult
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.NativeComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.RecordRequest
import kotlinx.coroutines.CancellationException

/** Supplies the engine features of the session that currently drives the computer, when there is one. */
internal fun interface NativeControlRouter {
    /** Features of the active engine session; `null` while no engine session runs. */
    suspend fun features(): EngineFeatures?
}

/** Default router: no engine declares a native computer control yet, so the host always works alone. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class NoNativeControlRouter : NativeControlRouter {
    override suspend fun features(): EngineFeatures? = null
}

/** The machine's capture effect shares native routing, cache import and host fallback for every caller. */
@Inject
internal class ComputerUseCaptureExecutor(
    private val coordinator: CaptureCoordinator,
    private val access: ComputerUseAccess,
    private val router: NativeControlRouter,
    private val presentation: ComputerUseCapturePresentation,
) {
    private val log = Log.tag("ComputerUseCaptureExecutor")

    /**
     * Captures only in the currently open session, after fresh toggle and operating system checks; the frame is
     * limited to the active [roi].
     */
    suspend fun capture(request: CaptureRequest, roi: CaptureRegion? = null): CaptureResult =
        presentation.withoutPresentation { captureWithoutPresentation(request, roi) }

    /**
     * Records keyframes of the open session with the host, only while the recording toggle is on. The session window
     * and indicators are hidden once for the whole clip (at most [RecordRequest.MAX_DURATION_MILLIS]): hiding them per
     * frame made them flicker and let a click on «Stop» fall through to the captured application. The presentation is
     * suppressed before the coordinator is locked, in the same order as a capture, so the two never wait on each other.
     */
    suspend fun record(request: RecordRequest, roi: CaptureRegion? = null): ClipOutcome =
        access.recordingFailure()?.let { ClipOutcome.Rejected(it) }
            ?: presentation.withoutPresentation { coordinator.record(request, roi) }

    private suspend fun captureWithoutPresentation(request: CaptureRequest, roi: CaptureRegion?): CaptureResult {
        val refusal = access.captureFailure()
        if (refusal != null) return CaptureResult(failure = refusal)
        val native = captureNative(request, roi)
        if (native != null) return native
        val fallbackRefusal = access.captureFailure()
        if (fallbackRefusal != null) return CaptureResult(failure = fallbackRefusal)
        return when (val outcome = coordinator.capture(request, roi)) {
            is CaptureOutcome.Produced -> outcome.result
            is CaptureOutcome.Rejected -> CaptureResult(failure = outcome.reason)
        }
    }

    private suspend fun captureNative(request: CaptureRequest, roi: CaptureRegion?): CaptureResult? {
        val mode = access.active()?.mode as? ComputerUseMode.Desktop ?: return null
        if (mode.monitor != null) return null
        val native = nativeControl() ?: return null
        val refusal = access.captureFailure()
        if (refusal != null) return CaptureResult(failure = refusal)
        val session = access.active()?.session ?: return null
        val captured = try {
            native.capture(request.copy(roi = null, tile = null, encoding = CapturePresets.Master))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "native capture failed, falling back to the host" }
            return null
        }
        val failure = access.captureFailure()
        if (failure != null) return CaptureResult(failure = failure)
        if (access.active()?.session != session) return CaptureResult(failure = ComputerUseFailure.Unavailable)
        val imported = coordinator.importNative(captured, request, roi)
        if (!imported.isUsable) {
            log.w { "native frame refused reason=${imported.failure?.name ?: "unusable"}, falling back to the host" }
        }
        return imported.takeIf { it.isUsable }
    }

    private suspend fun nativeControl(): NativeComputerControl? {
        if (!access.isNativeRoutingEnabled()) return null
        val features = router.features() ?: return null
        return when (val feature = features.resolve(NativeComputerControl)) {
            is FeatureAccess.Available -> feature.feature

            is FeatureAccess.Unavailable -> {
                log.w { "native computer control blocked failure=${feature.reason.code}" }
                null
            }

            FeatureAccess.Unsupported -> null
        }
    }
}
