package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessAuthor
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HarnessLifecycleTest {
    @Test
    fun `enabled work subscribes before load and closes admission before suspension`() = runTest {
        val signal = MutableSharedFlow<Unit>()
        val load = CompletableDeferred<Unit>()
        var starts = 0
        var closes = 0
        val storage = MemoryLibrary().apply {
            beforeLoad = {
                assertEquals(1, signal.subscriptionCount.value)
                load.await()
            }
        }
        val library = lazy { HarnessMachineFixture(backgroundScope, HarnessEffects(storage, RecordingRuntime())) }
        val work = lazy {
            object : HarnessEnabledWork {
                override fun start(scope: CoroutineScope) {
                    starts++
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { signal.collect {} }
                }
                override fun stop() {
                    assertFalse(library.value.state.value.isSuspended)
                    closes++
                }
            }
        }
        val enabled = MutableStateFlow(false)
        backgroundScope.launch { followHarnessToggle(library, enabled, work) }
        runCurrent()
        assertFalse(library.isInitialized())
        assertFalse(work.isInitialized())
        enabled.value = true
        runCurrent()
        assertEquals(1, starts)
        assertIs<HarnessState.Loading>(library.value.state.value)
        enabled.value = false
        runCurrent()
        assertEquals(1, closes)
        assertEquals(0, signal.subscriptionCount.value)
        assertTrue(library.value.state.value.isSuspended)
        load.complete(Unit)
    }

    @Test
    fun `off during initial load stays suspended and never activates until a later enable`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val storage = MemoryLibrary().apply { beforeLoad = { gate.await() } }
        val runtime = RecordingRuntime()
        val library = lazy { HarnessMachineFixture(backgroundScope, HarnessEffects(storage, runtime)) }
        val enabled = MutableStateFlow(false)
        val observer = backgroundScope.launch { followHarnessToggle(library, enabled) }
        runCurrent()
        assertFalse(library.isInitialized())
        enabled.value = true
        runCurrent()
        assertIs<HarnessState.Loading>(library.value.state.value)
        enabled.value = false
        runCurrent()
        assertTrue(library.value.state.value.isSuspended)
        gate.complete(Unit)
        runCurrent()
        assertIs<HarnessState.Ready>(library.value.state.value)
        assertTrue(runtime.activated.isEmpty())
        enabled.value = true
        runCurrent()
        assertEquals(1, runtime.activated.size)
        observer.cancelAndJoin()
        runCurrent()
        assertTrue(library.value.state.value.isSuspended)
        assertEquals(setOf(harness.id), runtime.deactivated.single().harnesses)
    }

    @Test
    fun `profile save survives toggle cancellation and reactivation uses the committed revision`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val storage = MemoryLibrary().apply { beforeSave = { gate.await() } }
        val runtime = RecordingRuntime()
        val library = lazy { HarnessMachineFixture(backgroundScope, HarnessEffects(storage, runtime)) }
        val enabled = MutableStateFlow(true)
        backgroundScope.launch { followHarnessToggle(library, enabled) }
        runCurrent()
        library.value.send(
            HarnessIntent.Public.Update(
                RequestId("edit"),
                harness.id,
                HarnessChange.Meta("Changed", ""),
                0,
                HarnessAuthor.User,
                now,
            ),
        )
        runCurrent()
        enabled.value = false
        runCurrent()
        assertEquals(1, assertIs<HarnessState.Ready>(library.value.state.value).pending.size)
        gate.complete(Unit)
        runCurrent()
        val committed = assertIs<HarnessState.Ready>(library.value.state.value)
        assertTrue(committed.pending.isEmpty())
        assertTrue(committed.isSuspended)
        assertEquals("Changed", committed.harnesses.single().harness.title)
        assertEquals(1, runtime.activated.size)
        enabled.value = true
        runCurrent()
        assertEquals(1L, runtime.activated.last().harness.revision)
        assertTrue(runtime.activated.last().generation > runtime.activated.first().generation)
    }
}
