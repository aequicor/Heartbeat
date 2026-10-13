package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentNatives
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.domain.OpenBrowserDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Native views of the profile's documents on desktop, on the app's CEF runtime: each view owns one browser, closed
 * when the view leaves the screen or the profile closes, and known to [browsers] until it has closed natively.
 * Documents use their own in-memory contexts, so neither the profile's browser storage nor clearing it concerns them.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<BrowserDocumentNatives>())
@Inject
internal class DesktopDocumentPanels(
    private val resources: DesktopBrowserResources,
    private val dispatchers: DispatcherProvider,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val browsers: DesktopDocumentViews,
) : BrowserDocumentNatives {
    private val panels = mutableSetOf<DesktopDocumentPanel>()

    init {
        scope.onClose { SwingUtilities.invokeLater { panels.toList().forEach(DesktopDocumentPanel::release) } }
    }

    /** A new view of [document]; called on EDT, released by its owner. */
    fun create(document: OpenBrowserDocument): DesktopDocumentPanel {
        check(SwingUtilities.isEventDispatchThread())
        return DesktopDocumentPanel(document, resources, dispatchers, scope.coroutineScope) { released ->
            panels -= released
            // The app scope outlives the profile, so the barrier holds until the native close completes.
            app.coroutineScope.launch {
                released.awaitClosed()
                browsers.closed(released)
            }
        }.also {
            panels += it
            browsers.register(it)
        }
    }
}

/**
 * One view of a document: a Swing container whose browser starts once the app's CEF runtime is ready (extracted on
 * IO, EDT stays free) and then follows the document's data. [unavailable] turns true when the runtime or the browser
 * is unavailable; the view then shows a note instead. All methods run on EDT.
 */
internal class DesktopDocumentPanel(
    private val document: OpenBrowserDocument,
    private val resources: DesktopBrowserResources,
    private val dispatchers: DispatcherProvider,
    parent: CoroutineScope,
    private val onReleased: (DesktopDocumentPanel) -> Unit,
) {
    private val log = Log.tag("DesktopDocumentPanel")
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val failure = MutableStateFlow(false)
    private var container: JPanel? = null
    private var engine: DesktopDocumentEngine? = null
    private val engines = mutableListOf<DesktopDocumentEngine>()

    @Volatile
    private var isReleased = false

    /** True once the runtime or the browser is unavailable for this view. */
    val unavailable: StateFlow<Boolean> = failure.asStateFlow()

    fun createComponent(): JComponent {
        check(SwingUtilities.isEventDispatchThread())
        container?.let { return it }
        val panel = DesktopBrowserContainer()
        container = panel
        if (!isReleased) start()
        return panel
    }

    fun release() {
        check(SwingUtilities.isEventDispatchThread())
        if (isReleased) return
        isReleased = true
        scope.cancel()
        engine?.release()
        engine = null
        container?.removeAll()
        container = null
        onReleased(this)
        log.v { "Document view released" }
    }

    /** Waits until every browser this view started has closed natively; call after [release]. */
    suspend fun awaitClosed() {
        val started = withContext(dispatchers.main) { engines.toList() }
        started.forEach { it.awaitClosed() }
    }

    private fun start() {
        scope.launch(dispatchers.main) {
            try {
                val app = resources.app()
                if (isReleased) return@launch
                val runtime = DesktopDocumentEngine(app, document, scope, dispatchers.default, { isReleased }, ::fail)
                engine = runtime
                engines += runtime
                container?.apply {
                    add(runtime.component, BorderLayout.CENTER)
                    revalidate()
                    repaint()
                }
                document.data.collect(runtime::deliver)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(error.redactedFailure()) { "Desktop document initialization failed" }
                fail()
            } catch (error: LinkageError) {
                log.e(error.redactedFailure()) { "Desktop document native runtime is unavailable" }
                fail()
            }
        }
    }

    private fun fail() {
        if (isReleased) return
        engine?.release()
        engine = null
        container?.removeAll()
        failure.value = true
        log.i { "Document view unavailable" }
    }
}
