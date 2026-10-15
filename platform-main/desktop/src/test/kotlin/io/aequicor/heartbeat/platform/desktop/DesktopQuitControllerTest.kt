package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopQuitControllerTest {
    @Test
    fun `restart during any pending cleanup stage preserves the installer`() = runTest {
        Stage.entries.forEach { stage ->
            val fixture = ShutdownFixture(this, stage)
            fixture.requests.quit()
            runCurrent()
            assertFalse("exited" in fixture.events)

            // The updater starts a child while an ordinary quit is already awaiting cleanup.
            fixture.isInstallerAlive = true
            withContext(Dispatchers.Default) { fixture.requests.restart() }
            fixture.requests.restart()
            fixture.requests.quit()
            fixture.release.complete(Unit)
            runCurrent()

            assertTrue(fixture.isInstallerAlive, "Installer must survive a restart during $stage")
            assertEquals(listOf("captured", "native", "scopes", "destroyed", "exited"), fixture.events)
        }
    }

    @Test
    fun `restart requested first cannot be downgraded by later quit gestures`() = runTest {
        val fixture = ShutdownFixture(this, Stage.Native)
        fixture.isInstallerAlive = true
        fixture.requests.restart()
        fixture.requests.quit()
        runCurrent()
        assertEquals(listOf("native"), fixture.events)

        fixture.requests.quit()
        fixture.release.complete(Unit)
        runCurrent()

        assertTrue(fixture.isInstallerAlive)
        assertEquals(listOf("native", "scopes", "destroyed", "exited"), fixture.events)
    }

    @Test
    fun `repeated ordinary quit requests perform one complete child sweep`() = runTest {
        val fixture = ShutdownFixture(this, Stage.Native)
        fixture.requests.quit()
        fixture.requests.quit()
        runCurrent()
        assertEquals(listOf("captured", "native"), fixture.events)

        fixture.requests.quit()
        fixture.release.complete(Unit)
        runCurrent()

        assertEquals(listOf("captured", "native", "scopes", "destroyed", "stopped", "exited"), fixture.events)
    }

    private class ShutdownFixture(scope: CoroutineScope, private val stage: Stage) {
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        var isInstallerAlive = false

        private val processes = object : DesktopProcessCleanup {
            override suspend fun capture() {
                events += "captured"
                if (stage == Stage.Capture) release.await()
            }

            override suspend fun stop() {
                events += "stopped"
                isInstallerAlive = false
            }
        }

        val requests = DesktopQuitController(scope, processes) { cleanup ->
            shutdownDesktop(
                setOf(
                    AppShutdownParticipant {
                        events += "native"
                        if (stage == Stage.Native) release.await()
                    },
                ),
                { events += "destroyed" },
                { events += "exited" },
                stopScopes = {
                    events += "scopes"
                    if (stage == Stage.Scopes) release.await()
                },
                processes = cleanup,
            )
        }.requests
    }

    private enum class Stage { Capture, Native, Scopes }
}
