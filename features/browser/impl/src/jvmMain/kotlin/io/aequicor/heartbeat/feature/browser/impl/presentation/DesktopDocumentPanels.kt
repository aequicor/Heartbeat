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
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentRegistry
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Native pages of the profile's documents on desktop: one retained browser per registration, closed when the
 * registration or profile closes, and known to [browsers] until it has closed natively. Moving or hiding a host only
 * detaches the page; a new host reuses its DOM and in-memory context.
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
    private val registry: BrowserDocumentRegistry,
) : BrowserDocumentNatives {
    private val panels = mutableMapOf<String, DesktopDocumentPanel>()

    init {
        scope.coroutineScope.launch(dispatchers.main) {
            registry.documents.collect { documents ->
                panels.filterKeys { it !in documents }.values.toList().forEach(DesktopDocumentPanel::release)
            }
        }
        scope.onClose { SwingUtilities.invokeLater { panels.values.toList().forEach(DesktopDocumentPanel::release) } }
    }

    /** The sole page of this still-open registration; stale compositions cannot resurrect a closed document. */
    fun retain(document: OpenBrowserDocument): DesktopDocumentPanel? {
        check(SwingUtilities.isEventDispatchThread())
        if (scope.isClosed || registry.documents.value[document.id] !== document) return null
        return panels[document.id] ?: create(document)
    }

    private fun create(document: OpenBrowserDocument): DesktopDocumentPanel =
        DesktopDocumentPanel(document, resources, dispatchers, scope.coroutineScope) { released ->
            panels.remove(document.id, released)
            // The app scope outlives the profile, so the barrier holds until the native close completes.
            app.coroutineScope.launch {
                released.awaitClosed()
                browsers.closed(released)
            }
        }.also {
            panels[document.id] = it
            browsers.register(it)
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
    private val presentation = DesktopDocumentPresentation()
    private var container: JPanel? = null
    private var engine: DesktopDocumentEngine? = null
    private var attachment: DesktopDocumentAttachment? = null
    private val engines = mutableListOf<DesktopDocumentEngine>()

    @Volatile
    private var isReleased = false

    /** True once the runtime or the browser is unavailable for this view. */
    val unavailable: StateFlow<Boolean> = failure.asStateFlow()

    /** Measurement of this native view, reset for every served page and revoked on release. */
    val contentSize: StateFlow<DesktopDocumentSize?> = presentation.measurements.size

    /** Theme changes affect the current DOM without replacing the document or its application data. */
    fun updateTheme(theme: DesktopDocumentTheme) {
        check(SwingUtilities.isEventDispatchThread())
        if (!isReleased) presentation.theme.value = theme
    }

    fun createComponent(): JComponent {
        check(SwingUtilities.isEventDispatchThread())
        container?.let { return it }
        val panel = DesktopBrowserContainer(onNativePaint = { engine?.nativePainted() })
        container = panel
        if (!isReleased) start()
        return panel
    }

    /** A host lease; creating it does not attach, start or duplicate the native page. */
    fun newAttachment(): DesktopDocumentAttachment = DesktopDocumentAttachment(this)

    /** EDT detach-before-attach prevents native content from belonging to two host windows at once. */
    internal fun attach(next: DesktopDocumentAttachment, host: JPanel) {
        check(SwingUtilities.isEventDispatchThread())
        if (isReleased) return
        attachment?.let(::detach)
        val content = createComponent()
        content.parent?.remove(content)
        attachment = next
        host.add(content, BorderLayout.CENTER)
        content.isVisible = true
        updateDismissRequest(next)
        host.revalidate()
        host.repaint()
    }

    /** A superseded host cannot detach the page or revoke the current host's keyboard callback. */
    internal fun detach(previous: DesktopDocumentAttachment) {
        check(SwingUtilities.isEventDispatchThread())
        if (attachment !== previous) return
        presentation.dismissal.update(null)
        attachment = null
        val content = container ?: return
        content.isVisible = false
        val parent = content.parent
        if (parent != null) {
            parent.remove(content)
            parent.revalidate()
            parent.repaint()
        }
    }

    internal fun updateDismissRequest(owner: DesktopDocumentAttachment) {
        check(SwingUtilities.isEventDispatchThread())
        if (attachment === owner && !isReleased) presentation.dismissal.update(owner.onDismissRequest)
    }

    fun release() {
        check(SwingUtilities.isEventDispatchThread())
        if (isReleased) return
        attachment?.let(::detach)
        isReleased = true
        scope.cancel()
        presentation.measurements.close()
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
                val runtime = DesktopDocumentEngine(
                    app,
                    document,
                    scope,
                    dispatchers.default,
                    { isReleased },
                    ::fail,
                    presentation,
                )
                engine = runtime
                engines += runtime
                container?.apply {
                    add(runtime.component, BorderLayout.CENTER)
                    revalidate()
                    repaint()
                }
                combine(document.data, presentation.theme) { data, _ -> data }.collect(runtime::deliver)
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
