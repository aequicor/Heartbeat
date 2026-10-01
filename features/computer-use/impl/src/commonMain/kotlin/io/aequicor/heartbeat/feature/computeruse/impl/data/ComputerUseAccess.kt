package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseDesktopInput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseNativeRouting
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseWindowMode
import io.aequicor.heartbeat.feature.computeruse.api.supports
import io.aequicor.heartbeat.feature.computeruse.impl.domain.OsPermissions
import kotlinx.coroutines.withContext

/** Revalidates live permissions and toggles before either service or machine operations touch the host. */
@Inject
internal class ComputerUseAccess(
    private val toggles: FeatureToggles,
    private val permissions: OsPermissions,
    private val machines: MachineRegistry,
    private val dispatchers: DispatcherProvider,
) {
    /** Current host capabilities; a disabled master switch reports no usable capability. */
    suspend fun probe(): ComputerUseCapabilities = withContext(dispatchers.io) {
        if (!toggles.get(ComputerUseEnabled)) {
            return@withContext ComputerUseCapabilities(
                isCaptureAvailable = false,
                isWindowCaptureAvailable = false,
                isInputAvailable = false,
                isDesktopInputAllowed = false,
                blockers = listOf(ComputerUseBlocker.UnsupportedPlatform),
            )
        }
        val fresh = permissions.probe()
        fresh.copy(
            isWindowCaptureAvailable = fresh.isWindowCaptureAvailable && toggles.get(ComputerUseWindowMode),
            isDesktopInputAllowed = fresh.isDesktopInputAllowed && toggles.get(ComputerUseDesktopInput),
        )
    }

    /** Current native-routing preference, read only after the operation passed the host guard. */
    suspend fun isNativeRoutingEnabled(): Boolean = toggles.get(ComputerUseNativeRouting)

    /** The active machine capture; no coordinator operation may silently create a session. */
    fun active(): ComputerUseState.Capturing? =
        machines.find(ComputerUseMachineKey)?.state?.value as? ComputerUseState.Capturing

    /** Refusal of a capture or crop, including changed permissions after the initial probe. */
    suspend fun captureFailure(isOpenRequired: Boolean = true): ComputerUseFailure? {
        val capabilities = probe()
        if (!capabilities.isCaptureAvailable) {
            return if (ComputerUseBlocker.UnsupportedPlatform in capabilities.blockers) {
                ComputerUseFailure.Unavailable
            } else {
                ComputerUseFailure.PermissionLost
            }
        }
        val capture = active() ?: return ComputerUseFailure.Unavailable
        if (isOpenRequired && !capture.isOpen) return ComputerUseFailure.Unavailable
        if (!capabilities.supports(capture.mode)) return ComputerUseFailure.ModeNotAllowed
        return null
    }

    /** Refusal of input: the machine owns arming and the live host owns permission availability. */
    suspend fun inputFailure(): ComputerUseFailure? {
        val captureFailure = captureFailure()
        if (captureFailure != null) return captureFailure
        val capture = active() ?: return ComputerUseFailure.Unavailable
        if (!capture.isInputArmed) return ComputerUseFailure.NotArmed
        val fresh = probe()
        if (!fresh.isInputAvailable) return ComputerUseFailure.PermissionLost
        if (capture.mode is ComputerUseMode.Desktop && !fresh.isDesktopInputAllowed) {
            return ComputerUseFailure.ModeNotAllowed
        }
        return null
    }
}
