package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.data.runtime.JvmHarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.services.forSend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessCallOriginsTest {
    @Test
    fun `merge preserves strongest ancestry and defensive snapshots cannot mutate origin`() {
        val owner = HarnessId("private_owner")
        val other = HarnessId("private_other")
        val source = mutableMapOf(owner to 3, other to 1)
        val restricted = HarnessCallOrigin(true, source)
        source[owner] = 0
        // Deliberately attack the read-only API with a mutable downcast.
        val snapshot = restricted.sendChain as MutableMap<HarnessId, Int>
        snapshot[owner] = 0
        val weaker = HarnessCallOrigin(false, mapOf(owner to 1, other to 2))
        val merged = restricted.merge(weaker)
        assertTrue(merged.isHookRestricted)
        assertEquals(mapOf(owner to 3, other to 2), merged.sendChain)
        assertEquals(merged, weaker.merge(restricted))
        assertEquals(mapOf(owner to 3, other to 1), restricted.sendChain)
        assertEquals(restricted, restricted.merge(HarnessCallOrigin()))
        assertFalse(restricted.toString().contains(owner.value))
        assertFalse(HarnessOriginContext(restricted).toString().contains(owner.value))
        assertFailsWith<IllegalArgumentException> { HarnessCallOrigin(sendChain = mapOf(owner to -1)) }
    }

    @Test
    fun `explicit origin survives suspension dispatcher switch and ordinary child without thread leakage`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val reused = executor.asCoroutineDispatcher()
        val origins = JvmHarnessCallOrigins()
        val expected = HarnessCallOrigin(true, mapOf(HarnessId("owner") to 2))
        try {
            withContext(origins.context(expected)) {
                assertOrigin(origins, expected)
                delay(1) // Virtual-time suspension exercises ThreadContextElement restore/install.
                assertOrigin(origins, expected)
                withContext(reused) {
                    assertOrigin(origins, expected)
                    coroutineScope { launch { assertOrigin(origins, expected) }.join() }
                }
                assertOrigin(origins, expected)
            }
            assertEquals(DETACHED_ORIGIN, origins.current())
            withContext(reused) {
                assertEquals(DETACHED_ORIGIN, origins.current())
                assertEquals(null, currentCoroutineContext()[HarnessOriginContext])
            }
        } finally {
            reused.close()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `scope cached before hook captures hook ancestry and survives callback return`() = runTest {
        val origins = JvmHarnessCallOrigins()
        val ownerId = HarnessId("owner")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val base = HarnessCallOrigin(sendChain = mapOf(ownerId to 1))
        val cachedScope = HarnessOriginScope(CoroutineScope(owner + dispatcher + origins.context(base)), origins)
        val hook = HarnessCallOrigin(true, mapOf(ownerId to 3))
        val release = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<HarnessCallOrigin>()
        try {
            val child = withContext(origins.context(hook)) {
                cachedScope.launch {
                    release.await()
                    delay(1)
                    assertOrigin(origins, hook)
                    observed.complete(origins.current())
                }
            }
            // The callback has returned; this child belongs to the cached activation scope, not that callback.
            assertEquals(DETACHED_ORIGIN, origins.current())
            assertFalse(observed.isCompleted)
            release.complete(Unit)
            child.join()
            assertEquals(hook, observed.await())
            // The hook never leaked into the cached scope; a neutral host carrier sees only its base ancestry.
            withContext(origins.context(HarnessCallOrigin())) {
                assertEquals(base, cachedScope.coroutineContext[HarnessOriginContext]?.origin)
            }
            // Escaped author code reading the cached scope without any carrier is restricted.
            assertTrue(cachedScope.coroutineContext[HarnessOriginContext]?.origin?.isHookRestricted == true)
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `nested carriers restore previous values and remain isolated between profiles`() = runTest {
        val first = JvmHarnessCallOrigins()
        val second = JvmHarnessCallOrigins()
        val hook = HarnessCallOrigin(true)
        val secondOrigin = HarnessCallOrigin(sendChain = mapOf(HarnessId("second") to 2))
        withContext(first.context(hook)) {
            assertEquals(hook, first.current())
            assertEquals(DETACHED_ORIGIN, second.current())
            withContext(second.context(secondOrigin)) {
                assertEquals(hook, first.current())
                assertEquals(secondOrigin, second.current())
            }
            assertEquals(hook, first.current())
            assertEquals(DETACHED_ORIGIN, second.current())
        }
        assertEquals(DETACHED_ORIGIN, first.current())
        assertEquals(DETACHED_ORIGIN, second.current())
    }

    @Test
    fun `author threads and fresh scopes without a carrier fail closed`() = runTest {
        val origins = JvmHarnessCallOrigins()
        val owner = HarnessId("owner")
        val unrestricted = HarnessCallOrigin(sendChain = mapOf(owner to 1))
        withContext(origins.context(unrestricted)) {
            assertEquals(unrestricted, origins.current())
            val fromThread = CompletableDeferred<HarnessCallOrigin>()
            thread { fromThread.complete(origins.current()) }.join()
            assertEquals(DETACHED_ORIGIN, fromThread.await())
            val executor = Executors.newSingleThreadExecutor()
            try {
                val fromScope = CoroutineScope(executor.asCoroutineDispatcher()).async { origins.current() }.await()
                assertEquals(DETACHED_ORIGIN, fromScope)
            } finally {
                executor.shutdown()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
        // A detached origin can never schedule a turn, whatever chain the escaped code believed it had.
        assertFailsWith<IllegalStateException> { DETACHED_ORIGIN.forSend(owner) }
    }

    private suspend fun assertOrigin(origins: HarnessCallOrigins, expected: HarnessCallOrigin) {
        assertEquals(expected, origins.current())
        assertEquals(expected, currentCoroutineContext()[HarnessOriginContext]?.origin)
    }
}
