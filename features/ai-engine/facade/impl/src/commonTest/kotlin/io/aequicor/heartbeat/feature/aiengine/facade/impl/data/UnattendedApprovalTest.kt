package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.common.WindowFocusState
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
import kotlin.test.assertNull
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
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus)
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
    fun `regaining focus restarts the wait`() = runTest {
        val focus = WindowFocusState().apply { update(false) }
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus)
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
        val permissions = ToolPermissions(TestSecuritySettings(SecurityMode.Ask), focus)
        val answer = CompletableDeferred<Boolean>()
        val result = async { permissions.authorize(context { answer.await() }, approval) { true } }
        runCurrent()
        advanceTimeBy(10.seconds)
        answer.complete(true)
        assertEquals(SecurityMode.Ask, result.await())
    }
}
