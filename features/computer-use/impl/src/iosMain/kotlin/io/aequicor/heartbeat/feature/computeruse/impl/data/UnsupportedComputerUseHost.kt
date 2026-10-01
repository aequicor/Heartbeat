package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.EncodedFrame
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameEncoder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.OsPermissions
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.RawFrame
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenCapturer
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.WindowCatalog

private const val REASON = "Computer use requires Desktop"

/** Reports the platform as unavailable instead of pretending to capture. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedScreenCapturer : ScreenCapturer {
    override suspend fun capture(mode: ComputerUseMode, region: CaptureRegion?, isCursorIncluded: Boolean): RawFrame? =
        throw UnsupportedOperationException(REASON)

    override suspend fun currentBounds(mode: ComputerUseMode): ScreenBounds? =
        throw UnsupportedOperationException(REASON)
}

/** No window list on this platform. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedWindowCatalog : WindowCatalog {
    override val isAvailable: Boolean = false
    override suspend fun list(): List<WindowTarget> = emptyList()
    override suspend fun resolve(id: WindowId): WindowTarget? = null
    override suspend fun activate(target: WindowTarget): Boolean = false
}

/** No input injection on this platform. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedInputInjector : InputInjector {
    override val isAvailable: Boolean = false

    override suspend fun apply(action: InputAction, map: (FramePoint) -> ScreenPoint?): InputOutcome =
        InputOutcome.Rejected(ComputerUseFailure.Unavailable)
}

/** Every probe answers "unsupported platform", which keeps the machine in `Unavailable`. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedOsPermissions : OsPermissions {
    private val log = Log.tag("UnsupportedOsPermissions")

    override suspend fun probe(): ComputerUseCapabilities {
        log.d { "computer use probed on an unsupported platform" }
        return ComputerUseCapabilities(
            isCaptureAvailable = false,
            isWindowCaptureAvailable = false,
            isInputAvailable = false,
            isDesktopInputAllowed = false,
            blockers = listOf(ComputerUseBlocker.UnsupportedPlatform),
        )
    }

    override suspend fun openSettings(blocker: ComputerUseBlocker) {
        log.i { "no system settings page on this platform blocker=$blocker" }
    }
}

/** No codec on this platform. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedFrameEncoder : FrameEncoder {
    override suspend fun encode(frame: PixelGrid, encoding: CaptureEncoding): EncodedFrame? =
        throw UnsupportedOperationException(REASON)

    override suspend fun decode(content: ByteArray): PixelGrid? = throw UnsupportedOperationException(REASON)
}

/** No frame storage on this platform. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedFrameStore : FrameStore {
    override suspend fun write(session: CaptureSessionId, id: CaptureId, frame: EncodedFrame): String =
        throw UnsupportedOperationException(REASON)

    override suspend fun read(path: String): ByteArray? = throw UnsupportedOperationException(REASON)
    override suspend fun delete(session: CaptureSessionId) = throw UnsupportedOperationException(REASON)
}
