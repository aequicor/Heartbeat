package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aistudio.impl.di.AiStudioGraph
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserRoute
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Metro registration, live gating and route lifetime, without starting a native browser. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserIntegrationTest {
    @Test
    fun `browser route observes the flag and ignores navigation while disabled`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        val lifecycle = LifecycleRegistry().apply { resume() }
        try {
            val flags = (app as TestToggleAccessors).toggleControl
            assertTrue(flags.registered.contains(BrowserEnabled))
            assertFalse(BrowserEnabled.default)
            val profile = app.profileSessions.open(ProfileId("browser"))
            (profile.graph as ProfileNavigation).navigation.create(
                DefaultComponentContext(lifecycle),
                listOf(BrowserRoute),
            )
            val machine = checkNotNull(app.machines.find(BrowserMachineKey))
            val disabled = machine.state.first {
                it is BrowserState.Running && it.isConfigured
            } as BrowserState.Running
            assertFalse(disabled.isEnabled)
            app.machines.send(BrowserMachineKey, BrowserIntent.Public.Open("example.org"))
            assertEquals("", (machine.state.value as BrowserState.Running).page.url)
            flags.setOverride(BrowserEnabled, true)
            machine.state.first { it is BrowserState.Running && it.isEnabled }
            app.machines.send(BrowserMachineKey, BrowserIntent.Public.Open("example.org"))
            machine.state.first { it is BrowserState.Running && it.page.url == "https://example.org" }
            app.machines.send(BrowserMachineKey, BrowserIntent.Public.Stop)
            runCurrent()
            assertFalse((machine.state.value as BrowserState.Running).page.isLoading)
            flags.setOverride(BrowserEnabled, false)
            val stopped = machine.state.first {
                it is BrowserState.Running && !it.isEnabled
            } as BrowserState.Running
            assertFalse(stopped.page.isLoading)
        } finally {
            lifecycle.destroy()
            (app.appScope as OwnedScope).close()
            app.appScope.coroutineScope.coroutineContext[Job]?.join()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }

    @Test
    fun `browser entry follows profile and flag independently of the AI runtime`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val flags = (app as TestToggleAccessors).toggleControl
            val profile = app.profileSessions.open(ProfileId("browser-entry"))
            val scope = app.scopes.child(profile.graph.scope, "browser-entry")
            val graph = (profile.graph as AiStudioGraph.Factory).createAiStudio(scope)
            val entries = (graph as StudioEntryTestAccessors).entries
            assertFalse(entries.showsBrowser.first())
            flags.setOverride(BrowserEnabled, true)
            assertTrue(entries.showsBrowser.first { it })
            flags.setOverride(BrowserEnabled, false)
            assertFalse(entries.showsBrowser.first { !it })
        } finally {
            (app.appScope as OwnedScope).close()
            app.appScope.coroutineScope.coroutineContext[Job]?.join()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
}
