package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseNativeRouting
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseStatus
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.EncodedFrame
import io.aequicor.heartbeat.feature.computeruse.api.HostComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.NativeCapture
import io.aequicor.heartbeat.feature.computeruse.api.NativeComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.OsPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * The host implementation of [HostComputerControl].
 *
 * While `computer_use.native_routing` is on and the driving engine resolves [NativeComputerControl], that
 * implementation runs first; a blocked or failing native call falls back to the host, because a debug session
 * must not end with "the engine could not look at the screen". Crops are always served by the host: only it
 * stores the master frames a crop is cut from.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class RoutedComputerControl(
    private val coordinator: CaptureCoordinator,
    private val permissions: OsPermissions,
    private val toggles: FeatureToggles,
    private val router: NativeControlRouter,
    private val machines: MachineRegistry,
    private val store: FrameStore,
    private val budget: VisionBudget,
    private val dispatchers: DispatcherProvider,
) : HostComputerControl {
    private val log = Log.tag("RoutedComputerControl")

    override suspend fun status(): ComputerUseStatus {
        val state = machines.find(ComputerUseMachineKey)?.state?.value
        val capabilities = state?.capabilities() ?: permissions.probe()
        return ComputerUseStatus(
            capabilities = capabilities,
            mode = (state as? ComputerUseState.Capturing)?.mode,
            isInputArmed = state?.isArmed() == true,
            lastPreview = (state as? ComputerUseState.Capturing)?.lastPreview
                ?: (state as? ComputerUseState.Ready)?.lastPreview,
        )
    }

    override suspend fun windows(): List<WindowTarget> = coordinator.targets()

    override suspend fun capture(request: CaptureRequest): CaptureResult {
        val native = nativeControl()
        if (native != null) {
            val captured = runNative("capture") { native.capture(request) }
            if (captured != null) {
                val stored = storeNative(captured)
                if (stored != null) return stored
            }
        }
        return when (val outcome = coordinator.capture(request)) {
            is CaptureOutcome.Produced -> outcome.result
            is CaptureOutcome.Rejected -> CaptureResult(failure = outcome.reason)
        }
    }

    override suspend fun crop(request: CropRequest): CaptureResult = when (val outcome = coordinator.crop(request)) {
        is CaptureOutcome.Produced -> outcome.result
        is CaptureOutcome.Rejected -> CaptureResult(failure = outcome.reason)
    }

    override suspend fun input(action: InputAction): InputOutcome {
        val native = nativeControl()
        if (native != null) {
            val outcome = runNative("input") { native.input(action) }
            if (outcome != null) return outcome
        }
        return coordinator.input(action)
    }

    override suspend fun revoke() {
        val sent = machines.send(ComputerUseMachineKey, ComputerUseIntent.Public.Revoke)
        if (sent == SendResult.Accepted) {
            log.i { "computer use revoked through the machine" }
            return
        }
        log.i { "computer use revoked without a running machine result=$sent" }
        coordinator.close()
        coordinator.purge()
    }

    private suspend fun nativeControl(): NativeComputerControl? {
        if (!toggles.get(ComputerUseNativeRouting)) return null
        val features = router.features() ?: return null
        return when (val access = features.resolve(NativeComputerControl)) {
            is FeatureAccess.Available -> access.feature

            is FeatureAccess.Unavailable -> {
                log.w { "native computer control blocked failure=${access.reason.code}" }
                null
            }

            FeatureAccess.Unsupported -> null
        }
    }

    private suspend fun <T> runNative(operation: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "native computer control $operation failed, falling back to the host" }
        null
    }

    /** Stores an engine-produced frame so that later crops address it like a host frame. */
    private suspend fun storeNative(capture: NativeCapture): CaptureResult? = withContext(dispatchers.io) {
        val session = (machines.find(ComputerUseMachineKey)?.state?.value as? ComputerUseState.Capturing)?.session
            ?: CaptureSessionId(NATIVE_SESSION_PREFIX)
        val id = CaptureId(Uuid.random().toString())
        val encoded = EncodedFrame(capture.format, capture.widthPx, capture.heightPx, capture.content)
        val stored = try {
            store.write(session, id, encoded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "native frame storage failed format=${capture.format}" }
            null
        } ?: return@withContext null
        val reference = CaptureRef(
            id = id,
            session = session,
            format = capture.format,
            widthPx = capture.widthPx,
            heightPx = capture.heightPx,
            region = CaptureRegion(0, 0, capture.widthPx, capture.heightPx),
            masterWidthPx = capture.widthPx,
            masterHeightPx = capture.heightPx,
            bytes = capture.content.size.toLong(),
            estimatedTokens = budget.cost.tokens(capture.widthPx, capture.heightPx),
            path = stored,
            sequence = 0L,
            isMaster = true,
        )
        log.i { "native frame stored id=$id format=${capture.format} size=${capture.widthPx}x${capture.heightPx}" }
        CaptureResult(reference = reference, master = reference)
    }

    private companion object {
        const val NATIVE_SESSION_PREFIX = "native"

        fun ComputerUseState.capabilities(): ComputerUseCapabilities? = when (this) {
            ComputerUseState.Idle -> null
            ComputerUseState.Checking -> null
            is ComputerUseState.Unavailable -> null
            is ComputerUseState.Ready -> capabilities
            is ComputerUseState.Capturing -> capabilities
            is ComputerUseState.Failed -> null
        }

        fun ComputerUseState.isArmed(): Boolean = when (this) {
            ComputerUseState.Idle -> false
            ComputerUseState.Checking -> false
            is ComputerUseState.Unavailable -> false
            is ComputerUseState.Ready -> isInputArmed
            is ComputerUseState.Capturing -> isInputArmed
            is ComputerUseState.Failed -> false
        }
    }
}
