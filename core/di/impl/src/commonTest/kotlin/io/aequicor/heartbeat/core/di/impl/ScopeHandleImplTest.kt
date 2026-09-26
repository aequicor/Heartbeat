package io.aequicor.heartbeat.core.di.impl

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeHandleImplTest {

    private fun TestScope.scope(name: String = "test") = ScopeHandleImpl(
        name = name,
        parentJob = null,
        dispatcher = StandardTestDispatcher(testScheduler),
        savedState = ScopeSavedStateImpl(name, restored = null, json = Json),
    )

    @Test
    fun close_actions_run_in_reverse_order_once() = runTest {
        val scope = scope()
        val events = mutableListOf<Int>()
        scope.onClose { events += 1 }
        scope.onClose { events += 2 }

        scope.close()
        scope.close()

        assertEquals(listOf(2, 1), events)
        assertTrue(scope.isClosed)
    }

    @Test
    fun action_registered_after_close_runs_immediately() = runTest {
        val scope = scope()
        scope.close()
        var ran = false

        scope.onClose { ran = true }

        assertTrue(ran)
    }

    @Test
    fun disposed_action_does_not_run() = runTest {
        val scope = scope()
        var ran = false
        scope.onClose { ran = true }.dispose()

        scope.close()

        assertFalse(ran)
    }

    @Test
    fun failing_action_does_not_prevent_the_others() = runTest {
        val scope = scope()
        var ran = false
        scope.onClose { ran = true }
        scope.onClose { error("boom") }

        scope.close()

        assertTrue(ran)
    }

    @Test
    fun failed_coroutine_does_not_cancel_the_scope_or_its_siblings() = runTest {
        val scope = scope()
        val sibling = scope.coroutineScope.launch { awaitCancellation() }
        scope.coroutineScope.launch { error("boom") }

        advanceUntilIdle()

        assertTrue(sibling.isActive)
        scope.close()
        advanceUntilIdle()
        assertTrue(sibling.isCancelled)
    }
}
