package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.impl.domain.OsPermissions
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PermissionGrants
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PermissionGuidePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
 * request replaces the running one. Revoking computer use or closing the profile cancels polling, pending
 * refreshes and the guide; a later start requires a new explicit grant request.
 */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class GuidedPermissionGrants(
    private val permissions: OsPermissions,
    private val guide: PermissionGuidePanel,
    private val access: ComputerUseAccess,
    private val machine: Lazy<Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>>,
    @ForScope(ProfileScope::class) profile: ScopeHandle,
) : PermissionGrants {
    private val log = Log.tag("PermissionGrants")

    // Buffered regardless of the collector's progress, so the first request is never lost.
    private val requests = Channel<ComputerUsePermission>(Channel.CONFLATED)

    init {
        profile.coroutineScope.launch {
            try {
                requests.receiveAsFlow().collectLatest(::grant)
            } finally {
                requests.cancel()
            }
        }
    }

    override fun request(permission: ComputerUsePermission) {
        val result = requests.trySend(permission)
        log.i { "permission grant requested permission=$permission queued=${result.isSuccess}" }
    }

    private suspend fun grant(permission: ComputerUsePermission) {
        try {
            grantWhileEnabled(permission)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "permission grant failed permission=$permission" }
        }
    }

    private suspend fun grantWhileEnabled(permission: ComputerUsePermission) = coroutineScope {
        val running = machine.value
        if (running.state.value == ComputerUseState.Idle) return@coroutineScope
        // Subscribe before OS work. Revoked also catches a brief Idle immediately followed by Start.
        val idle = launch(start = CoroutineStart.UNDISPATCHED) {
            running.state.first { it == ComputerUseState.Idle }
            this@coroutineScope.cancel()
        }
        val revoked = launch(start = CoroutineStart.UNDISPATCHED) {
            running.outputs.first { it == ComputerUseOutput.Revoked }
            this@coroutineScope.cancel()
        }
        try {
            if (awaitGrant(permission)) recheck()
        } finally {
            idle.cancel()
            revoked.cancel()
        }
    }

    /** `true` once the permission is granted; the guide is shown only while the user can still grant it. */
    private suspend fun awaitGrant(permission: ComputerUsePermission): Boolean {
        val blocker = permission.blocker
        if (!isMissing(blocker)) {
            log.i { "permission is already granted permission=$permission" }
            return true
        }
        if (!permissions.openSettings(permission)) {
            log.w { "permission settings did not open permission=$permission" }
            return false
        }
        val panel = guide.show(permission)
        try {
            val isGranted = withTimeoutOrNull(GUIDE_TIMEOUT) {
                while (isMissing(blocker)) delay(PROBE_INTERVAL)
                true
            } ?: false
            // Screen Recording often appears only after a restart, which macOS offers itself.
            log.i { "permission guide finished permission=$permission granted=$isGranted" }
            return isGranted
        } finally {
            panel.close()
        }
    }

    private suspend fun isMissing(blocker: ComputerUseBlocker): Boolean = blocker in permissions.probe().blockers

    /** Refreshes atomically when idle between captures; a raced capture delays, rather than loses, the refresh. */
    private suspend fun recheck() {
        val running = machine.value
        while (true) {
            val settled = running.state.first { it !is ComputerUseState.Capturing && it != ComputerUseState.Checking }
            if (settled == ComputerUseState.Idle) return
            val result = running.send(ComputerUseIntent.Internal.PermissionsRefreshed(access.probe()))
            log.i { "computer use refreshed after a permission grant result=$result" }
            if (result != SendResult.Ignored) return
        }
    }

    private companion object {
        val PROBE_INTERVAL = 1.seconds
        val GUIDE_TIMEOUT = 10.minutes
    }
}
