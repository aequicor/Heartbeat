package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.test.Test
import kotlin.test.assertFalse

class ComputerUsePermissionRefreshTest {
    @Test
    fun `refresh after stopping an agent preserves its fence and running cleanup effects`() {
        val stopped = ComputerUseState.Ready(
            capabilities.copy(isInputAvailable = false),
            isInputArmed = true,
            stoppedOwners = setOf(owner),
        )
        val refreshed = stopped.copy(capabilities = capabilities, isInputArmed = false)
        val resolution = ComputerUseMachineSpec.assertTransition(
            from = stopped,
            intent = ComputerUseIntent.Internal.PermissionsRefreshed(capabilities),
            to = refreshed,
        )
        assertFalse(resolution.isStateChange, "The StopOwner and capture cleanup effects must continue")
        ComputerUseMachineSpec.assertIgnored(
            refreshed,
            ComputerUseIntent.Public.BeginCapture(ComputerUseMode.Desktop(), owner, CaptureSessionId("again")),
        )
    }

    @Test
    fun `a refresh that loses capture permission preserves the stop fence and refuses capture`() {
        val stopped = ComputerUseState.Ready(capabilities, stoppedOwners = setOf(owner))
        val unavailable = capabilities.copy(isCaptureAvailable = false, isInputAvailable = false)
        val refreshed = stopped.copy(capabilities = unavailable)
        val resolution = ComputerUseMachineSpec.assertTransition(
            from = stopped,
            intent = ComputerUseIntent.Internal.PermissionsRefreshed(unavailable),
            to = refreshed,
        )
        assertFalse(resolution.isStateChange)
        ComputerUseMachineSpec.assertIgnored(
            refreshed,
            ComputerUseIntent.Public.BeginCapture(
                ComputerUseMode.Desktop(),
                CaptureOwner.Panel,
                CaptureSessionId("new"),
            ),
        )
    }

    @Test
    fun `refresh makes unavailable and failed states ready without another probe`() {
        listOf(
            ComputerUseState.Unavailable(listOf(ComputerUseBlocker.ScreenRecordingPermission)),
            ComputerUseState.Failed(ComputerUseFailure.Unavailable),
        ).forEach { initial ->
            ComputerUseMachineSpec.assertTransition(
                from = initial,
                intent = ComputerUseIntent.Internal.PermissionsRefreshed(capabilities),
                to = ComputerUseState.Ready(capabilities),
                effects = listOf(ComputerUseEffect.EnumerateWindows),
            )
        }
    }

    @Test
    fun `a still unavailable refresh reports the remaining blockers`() {
        val blocked = capabilities.copy(
            isCaptureAvailable = false,
            blockers = listOf(ComputerUseBlocker.ScreenRecordingPermission),
        )
        listOf(
            ComputerUseState.Unavailable(listOf(ComputerUseBlocker.AccessibilityPermission)),
            ComputerUseState.Failed(ComputerUseFailure.Unavailable),
        ).forEach { initial ->
            ComputerUseMachineSpec.assertTransition(
                from = initial,
                intent = ComputerUseIntent.Internal.PermissionsRefreshed(blocked),
                to = ComputerUseState.Unavailable(blocked.blockers),
                outputs = listOf(ComputerUseOutput.PermissionRequired(blocked.blockers)),
            )
        }
    }

    @Test
    fun `refresh cannot interrupt a capture or a probe and cannot restart revoked use`() {
        listOf(
            ComputerUseState.Idle,
            ComputerUseState.Checking,
            ComputerUseState.Capturing(
                CaptureSessionId("active"),
                ComputerUseMode.Desktop(),
                owner,
                capabilities,
            ),
        ).forEach { state ->
            ComputerUseMachineSpec.assertIgnored(state, ComputerUseIntent.Internal.PermissionsRefreshed(capabilities))
        }
    }

    private companion object {
        val capabilities = ComputerUseCapabilities(true, true, true, true)
        val owner = CaptureOwner.Agent(
            SessionRef(EngineId("pi"), SessionSourceId("source"), "native"),
            TurnId("stopped-turn"),
        )
    }
}
