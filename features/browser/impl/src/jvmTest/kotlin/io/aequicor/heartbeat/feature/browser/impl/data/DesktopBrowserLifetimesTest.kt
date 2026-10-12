package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserLifetimesTest {
    @Test
    fun `cancelled native disposal completes the barrier exceptionally and prevents reuse`() = runTest {
        val lifetimes = DesktopBrowserLifetimes(backgroundScope)
        val id = ProfileId("cancelled")
        val owner = lifetimes.register(id) { throw CancellationException("native teardown interrupted") }
        owner.close()
        assertFailsWith<CancellationException> { owner.awaitClosed() }
        val reopened = lifetimes.register(id) {}
        assertFailsWith<CancellationException> { reopened.awaitAvailable() }
        reopened.close()
        assertFailsWith<CancellationException> { reopened.awaitClosed() }
    }

    @Test
    fun `failed data clear still permits app exit after native closure completes`() = runTest {
        val lifetimes = DesktopBrowserLifetimes(backgroundScope)
        val nativeClosed = CompletableDeferred<Unit>()
        val profile = lifetimes.register(ProfileId("first")) { nativeClosed.await() }
        profile.invalidateStorage()
        val shutdown = async { lifetimes.closeAll() }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        nativeClosed.complete(Unit)
        shutdown.await()
        assertTrue(profile.isFinished)
    }

    @Test
    fun `failed clear blocks every reopened generation until profile storage is wiped`() = runTest {
        val lifetimes = DesktopBrowserLifetimes(backgroundScope)
        val id = ProfileId("first")
        val profile = lifetimes.register(id) {}
        profile.invalidateStorage()
        profile.close()
        profile.awaitClosed()
        repeat(2) {
            val reopened = lifetimes.register(id) {}
            assertFailsWith<IllegalStateException> { reopened.awaitAvailable() }
            reopened.close()
            reopened.awaitClosed()
        }
        lifetimes.storageWiped(id)
        val clean = lifetimes.register(id) {}
        clean.awaitAvailable()
        clean.close()
        lifetimes.closeAll()
    }

    @Test
    fun `reopening waits for old callbacks while another profile remains independent`() = runTest {
        val lifetimes = DesktopBrowserLifetimes(backgroundScope)
        val nativeClosed = CompletableDeferred<Unit>()
        val profile = ProfileId("first")
        val old = lifetimes.register(profile) { nativeClosed.await() }
        old.close()
        val reopened = lifetimes.register(profile) {}
        val available = async { reopened.awaitAvailable() }
        val unrelated = lifetimes.register(ProfileId("other")) {}
        unrelated.awaitAvailable()
        runCurrent()
        assertFalse(available.isCompleted)
        nativeClosed.complete(Unit)
        available.await()
        reopened.close()
        unrelated.close()
        lifetimes.closeAll()
    }

    @Test
    fun `profile wipe waits for native closure and rejects active owners`() = runTest {
        val lifetimes = DesktopBrowserLifetimes(backgroundScope)
        val profile = ProfileId("first")
        val nativeClosed = CompletableDeferred<Unit>()
        val owner = lifetimes.register(profile) { nativeClosed.await() }
        assertFailsWith<IllegalStateException> { lifetimes.awaitClosed(profile) }
        owner.close()
        val wipe = async { lifetimes.awaitClosed(profile) }
        runCurrent()
        assertFalse(wipe.isCompleted)
        nativeClosed.complete(Unit)
        wipe.await()
        lifetimes.awaitClosed(profile)
    }

    @Test
    fun `failed native teardown prevents reuse and profile wipe`() = runTest {
        val lifetimes = DesktopBrowserLifetimes(backgroundScope)
        val profile = ProfileId("first")
        val owner = lifetimes.register(profile) { error("native failure") }
        owner.close()
        assertFailsWith<IllegalStateException> { lifetimes.awaitClosed(profile) }
        val next = lifetimes.register(profile) {}
        assertFailsWith<IllegalStateException> { next.awaitAvailable() }
        next.close()
        assertFailsWith<IllegalStateException> { next.awaitClosed() }
    }

    @Test
    fun `app close awaits every generation and reports all native failures together`() = runTest {
        val lifetimes = DesktopBrowserLifetimes(backgroundScope)
        val nativeClosed = CompletableDeferred<Unit>()
        var isSlowClosed = false
        lifetimes.register(ProfileId("first")) { error("first failure") }
        lifetimes.register(ProfileId("slow")) {
            nativeClosed.await()
            isSlowClosed = true
        }
        lifetimes.register(ProfileId("cancelled")) { throw CancellationException("native teardown interrupted") }
        val shutdown = async { assertFailsWith<IllegalStateException> { lifetimes.closeAll() } }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        nativeClosed.complete(Unit)
        val failure = shutdown.await()
        assertTrue(isSlowClosed)
        assertEquals("first failure", failure.cause?.message)
        assertIs<CancellationException>(failure.suppressed.single())
        val retry = assertFailsWith<IllegalStateException> { lifetimes.closeAll() }
        assertEquals("first failure", retry.cause?.message)
    }

    @Test
    fun `profile wipe gives up waiting for a stuck native closure and can be retried`() = runTest {
        val lifetimes = DesktopBrowserLifetimes(backgroundScope)
        val profile = ProfileId("first")
        val nativeClosed = CompletableDeferred<Unit>()
        lifetimes.register(profile) { nativeClosed.await() }.close()
        val timeout = assertFailsWith<IllegalStateException> { lifetimes.awaitClosed(profile) }
        assertTrue(timeout.message.orEmpty().contains("retry"))
        nativeClosed.complete(Unit)
        lifetimes.awaitClosed(profile)
    }

    @Test
    fun `app close initiates all native closures even after app scope cancellation`() = runTest {
        val job = SupervisorJob()
        val app = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val lifetimes = DesktopBrowserLifetimes(app)
        val nativeClosed = CompletableDeferred<Unit>()
        var closed = 0
        lifetimes.register(ProfileId("first")) {
            nativeClosed.await()
            closed++
        }
        lifetimes.register(ProfileId("other")) { closed++ }
        job.cancel()
        val shutdown = async { lifetimes.closeAll() }
        runCurrent()
        assertEquals(1, closed)
        assertFalse(shutdown.isCompleted)
        assertFailsWith<IllegalStateException> { lifetimes.register(ProfileId("late")) {} }
        nativeClosed.complete(Unit)
        shutdown.await()
        assertEquals(2, closed)
        assertTrue(job.isCancelled)
    }
}
