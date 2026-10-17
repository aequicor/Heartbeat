package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserConnectivity
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserErrorPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserStorage
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserProfile
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cef.CefApp
import org.cef.browser.CefRequestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An agent session's Chromium storage lives exactly as long as its tabs: the factory, not the profile, knows which
 * panels still need it. Panels that were never shown start no native runtime, so the accounting runs without Chromium.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserPanelFactoryStorageTest {
    @Test
    fun `closing the last tab of an agent session asks the profile to discard its storage`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.open(BrowserStorage.Agent("session-a"))
        fixture.close(tab)
        assertEquals(listOf("session-a" to false), fixture.profile.releases)
    }

    @Test
    fun `a sibling tab keeps the session storage until it is closed too`() = runTest {
        val fixture = Fixture(this)
        val first = fixture.open(BrowserStorage.Agent("session-a"))
        val second = fixture.open(BrowserStorage.Agent("session-a"))
        fixture.close(first)
        assertEquals(listOf("session-a" to true), fixture.profile.releases)
        fixture.close(second)
        assertEquals(listOf("session-a" to true, "session-a" to false), fixture.profile.releases)
    }

    @Test
    fun `sessions are accounted separately and a user tab never discards agent storage`() = runTest {
        val fixture = Fixture(this)
        val mine = fixture.open(BrowserStorage.User)
        val agentA = fixture.open(BrowserStorage.Agent("session-a"))
        fixture.open(BrowserStorage.Agent("session-b"))
        fixture.close(mine)
        assertTrue(fixture.profile.releases.isEmpty())
        fixture.close(agentA)
        assertEquals(listOf("session-a" to false), fixture.profile.releases)
    }

    @Test
    fun `closing every surface for a shutdown leaves the discarding to the profile`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.open(BrowserStorage.Agent("session-a"))
        checkNotNull(fixture.profile.closeSurfaces)()
        runCurrent()
        fixture.close(tab)
        assertTrue(fixture.profile.releases.isEmpty())
    }

    @Test
    fun `a storage that cannot be discarded does not stop later tabs from being accounted`() = runTest {
        val fixture = Fixture(this)
        fixture.profile.failure = IllegalStateException("flush was not accepted")
        fixture.close(fixture.open(BrowserStorage.Agent("session-a")))
        fixture.close(fixture.open(BrowserStorage.Agent("session-b")))
        assertEquals(listOf("session-a" to false, "session-b" to false), fixture.profile.releases)
    }

    private class Fixture(private val scope: TestScope) {
        val profile = RecordingProfile()
        private val factory = DefaultDesktopBrowserPanelFactory(
            object : DesktopBrowserResources {
                override suspend fun app(): CefApp = error("The native runtime is not started by this test")
            },
            BrowserTestDispatchers(StandardTestDispatcher(scope.testScheduler)),
            BrowserUrlResolver { _, reference -> reference },
            BrowserTestScope(scope.backgroundScope),
            BrowserTestScope(scope.backgroundScope),
            profile,
            object : BrowserErrorPolicy {
                override fun observe() = flowOf(false)
            },
            object : BrowserConnectivity {
                override fun restored() = emptyFlow<Unit>()
            },
        )

        fun open(storage: BrowserStorage): BrowserSurface = Surface().also {
            factory.create(it, DesktopBrowserAgentGuard(), storage = storage)
        }

        suspend fun close(surface: BrowserSurface) {
            factory.close(surface)
            scope.runCurrent()
        }
    }

    /** Records what the factory asks of the profile; the native context is never reached. */
    private class RecordingProfile : DesktopBrowserProfile {
        override val isAvailable = true
        var closeSurfaces: (suspend () -> Unit)? = null
        var failure: Exception? = null

        /** The owner and what the factory's in-use check answered when the profile evaluated it. */
        val releases = mutableListOf<Pair<String, Boolean>>()

        override fun attach(closeSurfaces: suspend () -> Unit) {
            this.closeSurfaces = closeSurfaces
        }

        override suspend fun <T> useContext(block: (CefRequestContext) -> T): T = error("No native context")

        override suspend fun <T> useAgentContext(owner: String, block: (CefRequestContext) -> T): T =
            error("No native context")

        override suspend fun releaseAgentContext(owner: String, isInUse: () -> Boolean) {
            releases += owner to isInUse()
            failure?.let { throw it }
        }

        override suspend fun clearData() = Unit
    }

    private class Surface : BrowserSurface {
        override val nativeResources = BrowserNativeResources.None
        override val initialUrl = "https://example.com"
        override fun attached(controller: BrowserViewController) = Unit
        override fun changed(controller: BrowserViewController, state: BrowserViewState) = Unit
        override fun detached(controller: BrowserViewController) = Unit
    }
}
