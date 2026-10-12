package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineSpec
import io.aequicor.heartbeat.feature.browser.api.BrowserResearchAccess
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatMachineKey
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatOutput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchRecoveryAction
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Real research/attachment persistence across the browser machine boundary; no native E2E claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResearchBrowserIntegrationTest {
    @Test
    fun `explicit recovery replaces failed HTML and PDF sources once and persists their evidence`() = runTest {
        // Recovery timeouts must use real time while the loopback HTTP exchange runs on IO.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        val browser = RecoveryBrowserFixture()
        val lifecycle = LifecycleRegistry().apply { resume() }
        try {
            enableRecovery(app)
            val profile = app.profileSessions.open(ProfileId("browser-research"))
            val machine = openResearch(app, profile, lifecycle)
            startBrowser(app, profile, browser)
            val html = addSource(machine, "${browser.origin}/article")
            val pdf = addSource(machine, "${browser.origin}/document")
            openBlocked(machine, html)
            openBlocked(machine, pdf)
            assertEquals(2, browser.denials.get())
            assertEquals(0, browser.captures.get(), "Opening a source must not implicitly continue")
            browser.unlock()
            val recoveredHtml = recover(machine, html, ResearchRecoveryAction.Continue, ResearchLoadState.Ready)
            val recoveredPdf = recover(machine, pdf, ResearchRecoveryAction.Continue, ResearchLoadState.Ready)
            assertEquals(browser.text, recoveredHtml.text)
            assertEquals(html.id, recoveredHtml.id)
            assertEquals(pdf.id, recoveredPdf.id)
            verifyPdf(profile, recoveredPdf, pdf.value, browser.pdf)
            listOf(html, pdf).forEach { source ->
                machine.send(ResearchChatIntent.Public.RecoverResource(source.id, ResearchRecoveryAction.Continue))
            }
            assertEquals(2, browser.captures.get(), "Completed evidence must not be fetched or imported again")
            val saved = ready(machine).session!!.resources
            assertEquals(listOf(recoveredHtml, recoveredPdf), saved)
            lifecycle.destroy()
            app.profileSessions.close()
            verifyRestored(app, saved, pdf.value, browser.pdf)
        } finally {
            lifecycle.destroy()
            browser.close()
            app.closeAndAwaitStorages()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }

    private suspend fun enableRecovery(app: TestAppGraph) {
        val flags = (app as TestToggleAccessors).toggleControl
        listOf(ResearchChatEnabled, KoogEngineEnabled, AttachmentsEnabled, BrowserEnabled, BrowserResearchAccess)
            .forEach { flags.setOverride(it, true) }
    }

    private suspend fun startBrowser(app: TestAppGraph, profile: ProfileSession, fixture: RecoveryBrowserFixture) {
        val scope = app.scopes.child(profile.graph.scope, "recovery-browser-fixture")
        // The public registry addresses the latest instance; profile close unregisters both instances.
        val browser = (app as RecoveryMachineAccessors).launcher.launch(BrowserMachineSpec, scope, fixture)
        assertSame(browser.state, app.machines.find(BrowserMachineKey)?.state)
        browser.send(BrowserIntent.Public.Start)
        browser.state.first { it is BrowserState.Running && it.isEnabled }
    }

    private suspend fun openResearch(
        app: TestAppGraph,
        profile: ProfileSession,
        lifecycle: LifecycleRegistry,
    ): ResearchMachine {
        val target = EngineTarget(EngineId("koog"), EngineBindingId("recovery-test"), ModelId("model"))
        (profile.graph as ProfileNavigation).navigation.create(
            DefaultComponentContext(lifecycle),
            listOf(ResearchChatRoute(target)),
        )
        return assertNotNull(app.machines.find(ResearchChatMachineKey)).also { ready(it) }
    }

    private suspend fun addSource(machine: ResearchMachine, url: String): ResearchResource {
        assertEquals(
            SendResult.Accepted,
            machine.send(
                ResearchChatIntent.Public.AddResource(
                    ResearchResourceKind.Website,
                    url.substringAfterLast('/'),
                    url,
                    ResearchResourceScope.Question,
                ),
            ),
        )
        // Search rejects loopback URLs by policy. The browser fixture subsequently sees a real HTTP 403.
        val state = ready(machine) { it.session?.resources?.any { resource -> resource.value == url } == true }
        val source = state.session!!.resources.single { it.value == url }
        assertEquals(ResearchLoadState.Failed, source.loadState)
        assertEquals("fetch_failed", source.loadError)
        return source
    }

    private suspend fun openBlocked(machine: ResearchMachine, source: ResearchResource) {
        val pending = recover(machine, source, ResearchRecoveryAction.Open, ResearchLoadState.NeedsUserAction)
        assertEquals(source.id, pending.id)
        assertNotNull(pending.browserTabId, "Browser Open failed: ${pending.loadError}")
        assertNull(pending.attachmentId)
        assertEquals("", pending.text)
    }

    private suspend fun recover(
        machine: ResearchMachine,
        source: ResearchResource,
        action: ResearchRecoveryAction,
        expected: ResearchLoadState,
    ): ResearchResource {
        assertEquals(SendResult.Accepted, machine.send(ResearchChatIntent.Public.RecoverResource(source.id, action)))
        return ready(machine) { state ->
            state.session?.resources?.any { it.id == source.id && it.loadState == expected } == true
        }.session!!.resources.single { it.id == source.id }
    }

    private suspend fun verifyPdf(profile: ProfileSession, source: ResearchResource, url: String, bytes: ByteArray) {
        assertEquals(ResearchResourceKind.Document, source.kind)
        assertEquals(url, source.sourceUrl)
        assertFalse(source.hasAttachmentError)
        val attachments = profile.graph as TestAttachmentAccessors
        val descriptor = assertNotNull(attachments.catalog.get(assertNotNull(source.attachmentId)))
        assertEquals("application/pdf", descriptor.mediaType)
        assertEquals(bytes.size.toLong(), descriptor.sizeBytes)
        assertEquals(descriptor.resource.id, source.value)
        assertContentEquals(bytes, assertNotNull(attachments.resolver.resolve(descriptor.resource)).bytes)
    }

    private suspend fun verifyRestored(
        app: TestAppGraph,
        expected: List<ResearchResource>,
        url: String,
        bytes: ByteArray,
    ) {
        val profile = app.profileSessions.open(ProfileId("browser-research"))
        val lifecycle = LifecycleRegistry().apply { resume() }
        try {
            val restored = ready(openResearch(app, profile, lifecycle)).session!!.resources
            assertEquals(expected, restored)
            verifyPdf(profile, restored.single { it.kind == ResearchResourceKind.Document }, url, bytes)
        } finally {
            lifecycle.destroy()
        }
    }

    private suspend fun ready(
        machine: ResearchMachine,
        predicate: (ResearchChatState.Ready) -> Boolean = { true },
    ): ResearchChatState.Ready = withContext(Dispatchers.Default) {
        withTimeout(15_000) {
            val state = machine.state.first {
                it is ResearchChatState.Ready && (it.hasError || (!it.isMutating && predicate(it)))
            } as ResearchChatState.Ready
            assertFalse(state.hasError, "Research machine rejected the recovery/import operation")
            state
        }
    }
}

private typealias ResearchMachine = MachineRef<ResearchChatState, ResearchChatIntent.Public, ResearchChatOutput>

/** Public runtime access avoids importing or replacing browser/research implementation bindings. */
@ContributesTo(AppScope::class)
interface RecoveryMachineAccessors {
    val launcher: MachineLauncher
}
