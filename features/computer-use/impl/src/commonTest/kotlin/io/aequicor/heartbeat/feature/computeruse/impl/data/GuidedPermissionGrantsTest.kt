package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GuidedPermissionGrantsTest {
    @Test
    fun `a missing permission opens its settings and shows the guide until it is granted`() = runTest {
        val fixture = Fixture(this, missing(ComputerUseBlocker.ScreenRecordingPermission))
        fixture.grants.request(ComputerUsePermission.ScreenRecording)
        runCurrent()
        assertEquals(listOf(ComputerUsePermission.ScreenRecording), fixture.permissions.opened)
        assertEquals(ComputerUsePermission.ScreenRecording, fixture.guide.guide.value)
        assertEquals(emptyList(), fixture.machine.sent)

        fixture.permissions.capabilities = granted
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(fixture.guide.guide.value)
        assertEquals(listOf<ComputerUseIntent>(ComputerUseIntent.Public.Retry), fixture.machine.sent)
    }

    @Test
    fun `an already granted permission only refreshes the machine`() = runTest {
        val fixture = Fixture(this, granted)
        fixture.grants.request(ComputerUsePermission.Accessibility)
        runCurrent()
        assertEquals(emptyList(), fixture.permissions.opened)
        assertNull(fixture.guide.guide.value)
        assertEquals(listOf<ComputerUseIntent>(ComputerUseIntent.Public.Retry), fixture.machine.sent)
    }

    @Test
    fun `a host without the settings page shows no guide`() = runTest {
        val fixture = Fixture(this, missing(ComputerUseBlocker.AccessibilityPermission))
        fixture.permissions.isSettingsOpenable = false
        fixture.grants.request(ComputerUsePermission.Accessibility)
        runCurrent()
        assertEquals(listOf(ComputerUsePermission.Accessibility), fixture.permissions.opened)
        assertNull(fixture.guide.guide.value)
        assertEquals(emptyList(), fixture.machine.sent)
    }

    @Test
    fun `the guide ends after its timeout without a grant`() = runTest {
        val fixture = Fixture(this, missing(ComputerUseBlocker.ScreenRecordingPermission))
        fixture.grants.request(ComputerUsePermission.ScreenRecording)
        runCurrent()
        advanceTimeBy(10.minutes - 1.seconds)
        assertEquals(ComputerUsePermission.ScreenRecording, fixture.guide.guide.value)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertNull(fixture.guide.guide.value)
        assertEquals(emptyList(), fixture.machine.sent)
    }

    @Test
    fun `closing the panel keeps waiting for the grant`() = runTest {
        val fixture = Fixture(this, missing(ComputerUseBlocker.AccessibilityPermission))
        fixture.grants.request(ComputerUsePermission.Accessibility)
        runCurrent()
        fixture.guide.dismiss()
        assertNull(fixture.guide.guide.value)
        fixture.permissions.capabilities = granted
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf<ComputerUseIntent>(ComputerUseIntent.Public.Retry), fixture.machine.sent)
    }

    @Test
    fun `a newer request replaces the running guide`() = runTest {
        val fixture = Fixture(
            this,
            missing(ComputerUseBlocker.ScreenRecordingPermission, ComputerUseBlocker.AccessibilityPermission),
        )
        fixture.grants.request(ComputerUsePermission.ScreenRecording)
        runCurrent()
        fixture.grants.request(ComputerUsePermission.Accessibility)
        runCurrent()
        assertEquals(
            listOf(ComputerUsePermission.ScreenRecording, ComputerUsePermission.Accessibility),
            fixture.permissions.opened,
        )
        assertEquals(ComputerUsePermission.Accessibility, fixture.guide.guide.value)
        // Only the replacing request's permission ends its guide.
        fixture.permissions.capabilities = missing(ComputerUseBlocker.ScreenRecordingPermission)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(fixture.guide.guide.value)
        assertEquals(listOf<ComputerUseIntent>(ComputerUseIntent.Public.Retry), fixture.machine.sent)
    }

    @Test
    fun `closing the profile hides its guide`() = runTest {
        val fixture = Fixture(this, missing(ComputerUseBlocker.ScreenRecordingPermission))
        fixture.grants.request(ComputerUsePermission.ScreenRecording)
        runCurrent()
        fixture.closeProfile()
        runCurrent()
        assertNull(fixture.guide.guide.value)
    }

    @Test
    fun `a grant during a capture probes again once the capture ends`() = runTest {
        val fixture = Fixture(this, missing(ComputerUseBlocker.AccessibilityPermission))
        fixture.machine.state.value = ComputerUseState.Capturing(
            CaptureSessionId("session"),
            ComputerUseMode.Desktop(),
            CaptureOwner.Panel,
            granted,
        )
        fixture.grants.request(ComputerUsePermission.Accessibility)
        runCurrent()
        fixture.permissions.capabilities = granted
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(fixture.guide.guide.value)
        assertEquals(emptyList(), fixture.machine.sent)
        fixture.machine.state.value = ComputerUseState.Ready(granted)
        runCurrent()
        assertEquals(listOf<ComputerUseIntent>(ComputerUseIntent.Public.Retry), fixture.machine.sent)
    }

    @Test
    fun `a disabled tool is not started by a grant`() = runTest {
        val fixture = Fixture(this, granted, initial = ComputerUseState.Idle)
        fixture.grants.request(ComputerUsePermission.ScreenRecording)
        runCurrent()
        assertEquals(emptyList(), fixture.machine.sent)
    }

    private class Fixture(
        scope: TestScope,
        capabilities: ComputerUseCapabilities,
        initial: ComputerUseState = ComputerUseState.Unavailable(capabilities.blockers),
    ) {
        private val profileJob = Job(scope.backgroundScope.coroutineContext.job)
        val permissions = FakeOsPermissions(capabilities)
        val guide = DefaultComputerUsePermissionGuide()
        val machine = GrantMachine(initial)
        val grants = GuidedPermissionGrants(
            permissions,
            guide,
            lazyOf(machine),
            TestComputerUseScope(scope.backgroundScope + profileJob),
        )

        fun closeProfile() = profileJob.cancel()
    }
}

private val granted = ComputerUseCapabilities(
    isCaptureAvailable = true,
    isWindowCaptureAvailable = true,
    isInputAvailable = true,
    isDesktopInputAllowed = true,
)

private fun missing(vararg blockers: ComputerUseBlocker) = granted.copy(blockers = blockers.toList())

private class GrantMachine(initial: ComputerUseState) :
    Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput> {
    override val name = "computer-use"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ComputerUseOutput>()
    val sent = mutableListOf<ComputerUseIntent>()

    override suspend fun send(intent: ComputerUseIntent): SendResult = SendResult.Accepted.also { sent += intent }
}
