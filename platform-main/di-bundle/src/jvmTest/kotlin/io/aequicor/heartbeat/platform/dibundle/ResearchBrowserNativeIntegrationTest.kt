package io.aequicor.heartbeat.platform.dibundle

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserMachineKey
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResearchAccess
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserRoute
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real profile, UI surface, Chromium, research and attachment storage. Opt-in, run locally in its own JVM:
 * `./gradlew :platform-main:di-bundle:browserRecoveryNativeTest` (`jvmTest` skips it).
 */
class ResearchBrowserNativeIntegrationTest {
    @Test
    fun `human browser unlock persists actual HTML and PDF into the original research resources`() {
        assumeTrue(System.getProperty("heartbeat.browser.recoveryNativeTest") == "true")
        Log.init(isDebug = true, isTrace = true)
        runBlocking {
            val run = NativeResearchRun()
            val outcome = runCatching { withTimeout(180_000) { run.verify() } }
            val cleanup = runCatching {
                withContext(NonCancellable) { withTimeout(90_000) { run.close() } }
            }
            // Preserve the original failure, including cancellation, after the native shutdown barrier.
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                cleanup.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            cleanup.getOrThrow()
        }
    }
}

private class NativeResearchRun {
    private val persisted = PersistedProfile(
        storageRootOverride = requireNotNull(System.getProperty("heartbeat.browser.recoveryNativeRoot")),
    )
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val site = NativeResearchSite()
    private val lifecycle = LifecycleRegistry()
    private var window: ComposeWindow? = null

    suspend fun verify() {
        val profile = start()
        val research = assertNotNull(app.machines.find(ResearchChatMachineKey))
        val html = addSource(research, "${site.origin}/article")
        val pdf = addSource(research, "${site.origin}/document")
        val openedHtml = open(research, html)
        val openedPdf = open(research, pdf)
        assertTrue(site.deniedRequests.get() >= 2)
        assertEquals(0, site.unlockedRequests.get())
        assertEquals(0, site.documentRequests.get())
        unlock(assertNotNull(openedHtml.browserTabId))
        val readyHtml = resume(research, openedHtml)
        val readyPdf = resume(research, openedPdf)
        assertEquals(html.id, readyHtml.id)
        assertEquals(site.text, readyHtml.text.trim())
        assertEquals(pdf.id, readyPdf.id)
        assertEquals(pdf.value, readyPdf.sourceUrl)
        assertEquals(ResearchResourceKind.Document, readyPdf.kind)
        assertNull(readyPdf.loadError)
        val attachments = profile.graph as TestAttachmentAccessors
        val file = assertNotNull(attachments.catalog.get(assertNotNull(readyPdf.attachmentId)))
        assertEquals("application/pdf", file.mediaType)
        assertEquals(site.pdf.size.toLong(), file.sizeBytes)
        assertEquals(file.resource.id, readyPdf.value)
        assertContentEquals(site.pdf, assertNotNull(attachments.resolver.resolve(file.resource)).bytes)
        assertEquals(1, site.unlockedRequests.get(), "Only the explicit human action may unlock the source")
        assertEquals(1, site.documentRequests.get(), "Continue must download through the shared cookie context")
        assertEquals(2, ready(research).session!!.resources.size)
    }

    private suspend fun start(): ProfileSession {
        val flags = (app as TestToggleAccessors).toggleControl
        listOf(ResearchChatEnabled, KoogEngineEnabled, AttachmentsEnabled, BrowserEnabled, BrowserResearchAccess)
            .forEach { flags.setOverride(it, true) }
        return withContext(Dispatchers.Swing) {
            lifecycle.resume()
            val profile = app.profileSessions.open(ProfileId("native-research"))
            val navigation = (profile.graph as ProfileNavigation).navigation
            navigation.create(
                DefaultComponentContext(lifecycle),
                listOf(ResearchChatRoute(EngineTarget(EngineId("koog"), EngineBindingId("native"), ModelId("model")))),
                name = "native-research",
            )
            val browser = navigation.create(DefaultComponentContext(lifecycle), listOf(BrowserRoute), name = "browser")
            val component = browser.stack.value.active.instance as ComposableComponent
            window = ComposeWindow().apply {
                title = "Heartbeat native research recovery verification"
                setSize(1000, 700)
                setContent { HbTheme { component.Content(Modifier.fillMaxSize()) } }
                isVisible = true
            }
            profile
        }.also { ready(assertNotNull(app.machines.find(ResearchChatMachineKey))) }
    }

    private suspend fun addSource(machine: NativeResearchMachine, url: String): ResearchResource {
        machine.send(
            ResearchChatIntent.Public.AddResource(
                ResearchResourceKind.Website,
                url.substringAfterLast('/'),
                url,
                ResearchResourceScope.Question,
            ),
        )
        // Public search rejects loopback by policy; the actual browser then loads the fixture's HTTP 403.
        val state = ready(machine) { it.session?.resources?.any { source -> source.value == url } == true }
        return state.session!!.resources.single { it.value == url }.also {
            assertEquals(ResearchLoadState.Failed, it.loadState)
        }
    }

    private suspend fun open(machine: NativeResearchMachine, source: ResearchResource): ResearchResource {
        machine.send(ResearchChatIntent.Public.RecoverResource(source.id, ResearchRecoveryAction.Open))
        val pending = ready(machine) { state ->
            state.session?.resources?.any { it.id == source.id && it.loadState == ResearchLoadState.NeedsUserAction } ==
                true
        }.session!!.resources.single { it.id == source.id }
        val tab = assertNotNull(pending.browserTabId, "Browser startup failed: ${pending.loadError}")
        awaitTitle(tab, "Unlock source")
        return pending
    }

    private suspend fun unlock(tab: String) {
        val read = browserCall(tab, BrowserOperation.Read)
        assertTrue(read.data.getValue("content").jsonPrimitive.content.contains("Unlock"))
        // The public human-owned operation clicks only this controlled fixture, never a real CAPTCHA.
        browserCall(
            tab,
            BrowserOperation.Click,
            buildJsonObject {
                put("snapshotId", read.data.getValue("snapshotId"))
                put("element", 0)
            },
        )
        awaitTitle(tab, "Recovered source")
    }

    private suspend fun resume(machine: NativeResearchMachine, source: ResearchResource): ResearchResource {
        machine.send(ResearchChatIntent.Public.RecoverResource(source.id, ResearchRecoveryAction.Continue))
        // A settled failure asks for another human action; report it instead of waiting for the test timeout.
        return ready(machine) { state ->
            state.session?.resources?.any {
                val isFailed = it.loadState == ResearchLoadState.NeedsUserAction && it.loadError != null
                it.id == source.id && (it.loadState == ResearchLoadState.Ready || isFailed)
            } == true
        }.session!!.resources.single { it.id == source.id }.also {
            assertEquals(ResearchLoadState.Ready, it.loadState, "Continue failed: ${it.loadError}")
        }
    }

    private suspend fun awaitTitle(tab: String, title: String) {
        val browser = assertNotNull(app.machines.find(BrowserMachineKey))
        browser.state.first { state ->
            state is BrowserState.Running &&
                state.tabs.any { it.id == tab && it.page.title == title && !it.page.isLoading }
        }
        val state = browser.state.value as BrowserState.Running
        assertNull(state.tabs.single { it.id == tab }.owner)
    }

    private suspend fun browserCall(
        tab: String,
        operation: BrowserOperation,
        arguments: JsonObject = buildJsonObject { },
    ): BrowserResult = coroutineScope {
        val browser = assertNotNull(app.machines.find(BrowserMachineKey))
        val request = BrowserRequest(UUID.randomUUID().toString(), operation, tab, arguments = arguments)
        val output = async(start = CoroutineStart.UNDISPATCHED) {
            browser.outputs.first { it is BrowserOutput.Completed && it.result.requestId == request.id }
                as BrowserOutput.Completed
        }
        assertEquals(SendResult.Accepted, browser.send(BrowserIntent.Public.Perform(request)))
        output.await().result.also { assertNull(it.error, "Native browser operation failed") }
    }

    private suspend fun ready(
        machine: NativeResearchMachine,
        predicate: (ResearchChatState.Ready) -> Boolean = { true },
    ): ResearchChatState.Ready = machine.state.first {
        it is ResearchChatState.Ready && (it.hasError || (!it.isMutating && predicate(it)))
    }.let { it as ResearchChatState.Ready }.also { assertFalse(it.hasError) }

    /**
     * Flushes and terminates before cancelling scopes. Gradle removes files after this worker exits: Windows keeps
     * the extracted JCEF DLLs mapped until process termination. Every step runs even when an earlier one fails;
     * the first failure is thrown with the others suppressed. Cancellation stops cleanup at once.
     */
    suspend fun close() {
        var failure: Throwable? = null
        suspend fun step(action: suspend () -> Unit) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                log.w(error) { "Native research cleanup step failed; continuing with the remaining steps" }
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }
        (app as AppShutdownAccessors).shutdownParticipants.forEach { participant -> step { participant.shutdown() } }
        step { withContext(Dispatchers.Swing) { window?.dispose() } }
        step { withContext(Dispatchers.Swing) { lifecycle.destroy() } }
        step { withContext(Dispatchers.Swing) { app.closeAndAwaitStorages() } }
        step { site.close() }
        failure?.let { throw it }
    }
}

private val log = Log.tag("NativeResearchRun")

private typealias NativeResearchMachine = MachineRef<ResearchChatState, ResearchChatIntent.Public, ResearchChatOutput>
