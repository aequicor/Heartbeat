package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.common.WindowFocusState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestEngine
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestSecuritySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UnattendedApprovalTest {
    private val approval = AgentToolApproval("bash", "Native tool: bash", "{}")

    private fun context(request: suspend () -> Boolean) = AgentToolContext(
        SessionRef(TestEngine, SessionSourceId("test"), "session"),
        null,
        TurnId("turn"),
        permissions = AgentToolPermissions { request() },
    )

    @Test
    fun `request is declined after thirty seconds without window focus`() = runTest {
        val focus = WindowFocusState().apply { update(false) }
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus, isTimeoutEnabled = { true })
        val result = async { permissions.authorize(context { awaitCancellation() }, approval) { true } }
        runCurrent()
        advanceTimeBy(29.seconds)
        assertFalse(result.isCompleted)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertNull(result.await())
    }

    @Test
    fun `request stays pending while the window is focused`() = runTest {
        val focus = WindowFocusState()
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus, isTimeoutEnabled = { true })
        val answer = CompletableDeferred<Boolean>()
        val result = async { permissions.authorize(context { answer.await() }, approval) { true } }
        runCurrent()
        advanceTimeBy(600.seconds)
        assertFalse(result.isCompleted)
        answer.complete(true)
        assertEquals(SecurityMode.Ask, result.await())
    }

    @Test
    fun `regaining focus restarts the wait`() = runTest {
        val focus = WindowFocusState().apply { update(false) }
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus, isTimeoutEnabled = { true })
        val answer = CompletableDeferred<Boolean>()
        val result = async { permissions.authorize(context { answer.await() }, approval) { true } }
        runCurrent()
        advanceTimeBy(20.seconds)
        focus.update(true)
        runCurrent()
        advanceTimeBy(60.seconds)
        assertFalse(result.isCompleted)
        focus.update(false)
        runCurrent()
        advanceTimeBy(29.seconds)
        assertFalse(result.isCompleted)
        answer.complete(true)
        assertEquals(SecurityMode.Ask, result.await())
    }

    @Test
    fun `an answer before the timeout wins even without focus`() = runTest {
        val focus = WindowFocusState().apply { update(false) }
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus, isTimeoutEnabled = { true })
        val answer = CompletableDeferred<Boolean>()
        val result = async { permissions.authorize(context { answer.await() }, approval) { true } }
        runCurrent()
        advanceTimeBy(10.seconds)
        answer.complete(true)
        assertEquals(SecurityMode.Ask, result.await())
    }

    @Test
    fun `timeout is not a refusal of the action and says so`() = runTest {
        val focus = WindowFocusState().apply { update(false) }
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus, isTimeoutEnabled = { true })
        val result = async {
            permissions.authorizeAction(context { awaitCancellation() }, approval) { ActionSafetyDecision.Ask }
        }
        runCurrent()
        advanceTimeBy(31.seconds)
        runCurrent()
        val denied = assertIs<ToolAuthorization.Denied>(result.await())
        assertTrue("out of focus" in denied.reason, denied.reason)
    }

    @Test
    fun `a deliberate refusal keeps its own reason`() = runTest {
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask))
        val result = permissions.authorizeAction(context { false }, approval) { ActionSafetyDecision.Ask }
        assertEquals(ToolAuthorization.Denied("The user declined this action"), result)
    }

    @Test
    fun `nothing expires while the toggle is off`() = runTest {
        val focus = WindowFocusState().apply { update(false) }
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus)
        val answer = CompletableDeferred<Boolean>()
        val result = async { permissions.authorize(context { answer.await() }, approval) { true } }
        runCurrent()
        advanceTimeBy(600.seconds)
        assertFalse(result.isCompleted)
        answer.complete(true)
        assertEquals(SecurityMode.Ask, result.await())
    }

    @Test
    fun `a mode change during the wait asks again with a fresh timeout`() = runTest {
        val focus = WindowFocusState().apply { update(false) }
        val settings = TestSecuritySettings(SecurityMode.Ask)
        val permissions = ToolPermissions(settings, focus, isTimeoutEnabled = { true })
        var requests = 0
        val result = async {
            val asking = context {
                requests++
                awaitCancellation()
            }
            permissions.authorize(asking, approval) { it != SecurityMode.Trust }
        }
        runCurrent()
        advanceTimeBy(20.seconds)
        settings.save(SecurityMode.Automatic)
        runCurrent()
        advanceTimeBy(20.seconds)
        assertFalse(result.isCompleted)
        assertEquals(2, requests)
        advanceTimeBy(11.seconds)
        runCurrent()
        assertNull(result.await())
    }
}
