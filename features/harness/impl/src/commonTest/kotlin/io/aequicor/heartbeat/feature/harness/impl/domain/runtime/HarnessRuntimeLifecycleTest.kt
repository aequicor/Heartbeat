package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessRuntimeLifecycleTest {
    @Test
    fun `failed replacement preserves old instance then successful replacement drains it`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val old = fixture.activate()
        fixture.desired = runtimeRequest(2)
        fixture.isEvaluationSuccessful = false
        assertFalse(fixture.runtime.activate(fixture.desired))
        runCurrent()
        assertSame(old, fixture.runtime.instance(old.request.harness.id, old.request.item.id))
        assertEquals(listOf(0, 1), fixture.code.map { it.closes })
        fixture.isEvaluationSuccessful = true
        val replacement = fixture.activate()
        runCurrent()
        assertEquals(2L, replacement.request.generation)
        assertTrue(old.isClosed)
        assertEquals(listOf(1, 1, 0), fixture.code.map { it.closes })
    }

    @Test
    fun `quota commit rejection preserves published generation and closes rejected candidate`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val old = fixture.activate()
        fixture.desired = runtimeRequest(2)
        fixture.isPublicationCommitAllowed = false
        val publication = CompletableDeferred<Boolean>()
        fixture.onCreate = { access ->
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                publication.complete(access.awaitPublication())
            }
        }
        fixture.onPublicationCommit = { access ->
            assertFalse(access.isActive)
            assertFalse(access.isRegistrationAllowed)
            assertFalse(publication.isCompleted)
        }
        assertFalse(fixture.runtime.activate(fixture.desired))
        runCurrent()
        assertSame(old, fixture.runtime.publishedInstances.value.single())
        assertEquals(2, fixture.publicationCommits)
        assertFalse(publication.await())
        assertTrue(old.isActive)
        assertFalse(fixture.accesses.last().isActive)
        assertEquals(listOf(0, 1), fixture.code.map { it.closes })
        fixture.isPublicationCommitAllowed = true
        fixture.onPublicationCommit = { access -> assertFalse(access.isActive) }
        val replacement = fixture.activate()
        runCurrent()
        assertSame(replacement, fixture.runtime.publishedInstances.value.single())
        assertTrue(old.isClosed)
    }

    @Test
    fun `failed sealing never commits shared publication quotas`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.isContextReady = false
        assertFalse(fixture.runtime.activate(fixture.desired))
        runCurrent()
        assertEquals(0, fixture.publicationCommits)
        assertEquals(1, fixture.contextCloses)
        assertTrue(fixture.runtime.publishedInstances.value.isEmpty())
    }

    @Test
    fun `retirement closes admissions but preserves admitted call and code until actual finish`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val instance = fixture.activate()
        val gate = CompletableDeferred<Unit>()
        val call = async {
            fixture.runtime.invoke(instance, 30.seconds) {
                gate.await()
                "done"
            }
        }
        runCurrent()
        assertFalse(fixture.runtime.deactivate(fixture.deactivation()))
        assertNull(fixture.runtime.invoke(instance, 30.seconds) { error("Retired callback entered") })
        runCurrent()
        assertFalse(instance.isClosed)
        assertEquals(0, fixture.code.single().closes)
        gate.complete(Unit)
        call.await()
        runCurrent()
        assertTrue(instance.isClosed)
        assertTrue(fixture.runtime.deactivate(fixture.deactivation()))
        assertEquals(1, fixture.code.single().closes)
    }

    @Test
    fun `snapshot suspension rejects publication before its deactivation effect arrives`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        fixture.beforeEvaluation = { gate.await() }
        val activation = async { fixture.runtime.activate(fixture.desired) }
        runCurrent()
        fixture.isEnabled = false
        gate.complete(Unit)
        assertFalse(activation.await())
        runCurrent()
        assertEquals(1, fixture.code.single().closes)
        assertNull(fixture.runtime.instance(fixture.desired.harness.id, fixture.desired.item.id))
    }

    @Test
    fun `late old compile cannot replace new generation or leak its artifact`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val oldRequest = fixture.desired
        val gate = CompletableDeferred<Unit>()
        fixture.beforeCompile = { request ->
            if (request.source == "revision1") withContext(NonCancellable) { gate.await() }
        }
        val old = async { fixture.runtime.activate(oldRequest) }
        runCurrent()
        fixture.desired = runtimeRequest(2)
        val replacing = async { fixture.activate() }
        runCurrent()
        assertFalse(old.await())
        assertFalse(replacing.isCompleted)
        gate.complete(Unit)
        val replacement = replacing.await()
        runCurrent()
        assertSame(replacement, fixture.runtime.instance(replacement.request.harness.id, replacement.request.item.id))
        assertEquals(listOf(1, 0), fixture.code.map { it.closes })
    }

    @Test
    fun `delayed cleanup fences only its generations and cannot unload replacement`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val old = fixture.activate()
        fixture.desired = runtimeRequest(2)
        val replacement = fixture.activate()
        fixture.runtime.deactivate(fixture.deactivation(old.request))
        runCurrent()
        assertSame(replacement, fixture.runtime.instance(replacement.request.harness.id, replacement.request.item.id))
        assertTrue(old.isClosed)
        assertFalse(replacement.isClosed)
    }

    @Test
    fun `profile close retains code while actual call ignores cancellation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val profile = SupervisorJob(backgroundScope.coroutineContext[Job])
        val fixture = HarnessRuntimeFixture(CoroutineScope(backgroundScope.coroutineContext + profile), dispatcher)
        val instance = fixture.activate()
        val gate = CompletableDeferred<Unit>()
        val call = async {
            fixture.runtime.invoke(instance, 30.seconds) { withContext(NonCancellable) { gate.await() } }
        }
        runCurrent()
        profile.cancel()
        runCurrent()
        assertFalse(instance.isClosed)
        assertEquals(0, fixture.code.single().closes)
        gate.complete(Unit)
        call.cancelAndJoin()
        profile.join()
        assertTrue(instance.isClosed)
        assertEquals(1, fixture.code.single().closes)
    }

    @Test
    fun `cancelled profile never creates a candidate or evaluates code`() = runTest {
        val profile = SupervisorJob().apply { cancel() }
        val fixture = HarnessRuntimeFixture(CoroutineScope(profile), StandardTestDispatcher(testScheduler))
        assertFalse(fixture.runtime.activate(fixture.desired))
        assertTrue(fixture.code.isEmpty())
        assertTrue(fixture.accesses.isEmpty())
    }
}
