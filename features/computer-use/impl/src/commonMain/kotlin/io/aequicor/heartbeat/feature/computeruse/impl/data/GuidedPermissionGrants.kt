package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.impl.domain.OsPermissions
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PermissionGrants
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PermissionGuidePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Profile-owned grant flow: opens the permission's system settings page, shows the host's drag guide and polls the
 * operating system until the permission appears, then hides the guide and lets the machine probe again. A newer
 * request replaces the running one; closing the profile ends it and hides its guide.
 */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class GuidedPermissionGrants(
    private val permissions: OsPermissions,
    private val guide: PermissionGuidePanel,
    private val machine: Lazy<Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>>,
    @ForScope(ProfileScope::class) profile: ScopeHandle,
) : PermissionGrants {
    private val log = Log.tag("PermissionGrants")

    // Buffered regardless of the collector's progress, so the first request is never lost.
    private val requests = Channel<ComputerUsePermission>(Channel.CONFLATED)

    init {
        profile.coroutineScope.launch { requests.receiveAsFlow().collectLatest(::grant) }
    }

    override fun request(permission: ComputerUsePermission) {
        val result = requests.trySend(permission)
        log.i { "permission grant requested permission=$permission queued=${result.isSuccess}" }
    }

    private suspend fun grant(permission: ComputerUsePermission) {
        try {
            if (awaitGrant(permission)) recheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "permission grant failed permission=$permission" }
        }
    }

    /** `true` once the permission is granted; the guide is shown only while the user can still grant it. */
    private suspend fun awaitGrant(permission: ComputerUsePermission): Boolean {
        val blocker = permission.blocker()
        if (!isMissing(blocker)) {
            log.i { "permission is already granted permission=$permission" }
            return true
        }
        if (!permissions.openSettings(permission)) {
            log.w { "permission settings did not open permission=$permission" }
            return false
        }
        guide.show(permission)
        try {
            val isGranted = withTimeoutOrNull(GUIDE_TIMEOUT) {
                while (isMissing(blocker)) delay(PROBE_INTERVAL)
                true
            } ?: false
            // Screen Recording often appears only after a restart, which macOS offers itself.
            log.i { "permission guide finished permission=$permission granted=$isGranted" }
            return isGranted
        } finally {
            guide.hide(permission)
        }
    }

    private suspend fun isMissing(blocker: ComputerUseBlocker): Boolean = blocker in permissions.probe().blockers

    /** Refreshes the machine's capabilities; a running capture keeps its own until it ends. */
    private suspend fun recheck() {
        val running = machine.value
        val settled = running.state.first { it !is ComputerUseState.Capturing && it != ComputerUseState.Checking }
        when (settled) {
            is ComputerUseState.Unavailable, is ComputerUseState.Failed, is ComputerUseState.Ready -> {
                val result = running.send(ComputerUseIntent.Public.Retry)
                log.i { "computer use probes again after a permission grant result=$result" }
            }

            ComputerUseState.Idle -> log.d { "permission granted while computer use is off" }

            ComputerUseState.Checking, is ComputerUseState.Capturing -> Unit
        }
    }

    private fun ComputerUsePermission.blocker(): ComputerUseBlocker = when (this) {
        ComputerUsePermission.ScreenRecording -> ComputerUseBlocker.ScreenRecordingPermission
        ComputerUsePermission.Accessibility -> ComputerUseBlocker.AccessibilityPermission
    }

    private companion object {
        val PROBE_INTERVAL = 1.seconds
        val GUIDE_TIMEOUT = 10.minutes
    }
}
