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
import io.aequicor.heartbeat.core.logging.HighFrequency
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
import kotlinx.coroutines.flow.first
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
 * One view of a document: a Swing container whose browser starts once the app's CEF runtime is ready (downloaded
 * and extracted on IO, EDT stays free) and then follows the document's data. Failed preparation can be retried on
 * the same registration; callbacks of earlier attempts cannot change the new page. All methods run on EDT.
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
    private val preparationState = MutableStateFlow<BrowserRuntimeUi>(BrowserRuntimeUi.Preparing)
    private val presentation = DesktopDocumentPresentation()
    private var container: JPanel? = null
    private var engine: DesktopDocumentEngine? = null
    private var attachment: DesktopDocumentAttachment? = null
    private val engines = mutableListOf<DesktopDocumentEngine>()
    private var initialization: Job? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var isReleased = false

    /** True once the runtime or the browser is unavailable for this view. */
    val unavailable: StateFlow<Boolean> = failure.asStateFlow()

    /** Preparation is shared by every attachment of this document, just like its retained DOM. */
    val preparation = preparationState.asStateFlow()

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

    /** Retries only a failed attempt; repeated clicks and closed registrations cannot start another browser. */
    fun retry() {
        check(SwingUtilities.isEventDispatchThread())
        if (!isReleased && failure.value) start()
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
        updateFocusExitRequest(next)
        updateCornerRadius(next)
        host.revalidate()
        host.repaint()
    }

    /** A superseded host cannot detach the page or revoke the current host's keyboard callback. */
    internal fun detach(previous: DesktopDocumentAttachment) {
        check(SwingUtilities.isEventDispatchThread())
        if (attachment !== previous) return
        presentation.dismissal.update(null)
        presentation.focusExit.update(null)
        engine?.updateCornerRadius(0.0)
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

    internal fun updateFocusExitRequest(owner: DesktopDocumentAttachment) {
        check(SwingUtilities.isEventDispatchThread())
        if (attachment === owner && !isReleased) presentation.focusExit.update(owner.onFocusExitRequest)
    }

    internal fun updateCornerRadius(owner: DesktopDocumentAttachment) {
        check(SwingUtilities.isEventDispatchThread())
        if (attachment === owner && !isReleased) engine?.updateCornerRadius(owner.cornerRadius)
    }

    fun release() {
        check(SwingUtilities.isEventDispatchThread())
        if (isReleased) return
        attachment?.let(::detach)
        isReleased = true
        generation++
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
        val starting = withContext(dispatchers.main) { initialization }
        starting?.join()
        val started = withContext(dispatchers.main) { engines.toList() }
        started.forEach { it.awaitClosed() }
    }

    private fun start() {
        initialization?.cancel()
        val attempt = ++generation
        failure.value = false
        preparationState.value = BrowserRuntimeUi.Preparing
        initialization = scope.launch(dispatchers.main) {
            val progress = observePreparation(attempt)
            try {
                val app = resources.app()
                if (isReleased || attempt != generation) return@launch
                val runtime = DesktopDocumentEngine(
                    app,
                    document,
                    scope,
                    dispatchers.default,
                    { isReleased || attempt != generation },
                    { fail(attempt) },
                    presentation,
                )
                engines += runtime
                if (isReleased || attempt != generation) {
                    runtime.release()
                    return@launch
                }
                engine = runtime
                preparationState.value = BrowserRuntimeUi.Ready
                progress.cancel()
                runtime.updateCornerRadius(attachment?.cornerRadius ?: 0.0)
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
                failPreparation(attempt)
            } catch (error: LinkageError) {
                log.e(error.redactedFailure()) { "Desktop document native runtime is unavailable" }
                failPreparation(attempt)
            } finally {
                progress.cancel()
            }
        }
    }

    @HighFrequency
    private fun observePreparation(attempt: Int): Job = scope.launch(dispatchers.main) {
        resources.preparation.collect {
            if (!isReleased && attempt == generation && engine == null) {
                log.v { "Document preparation progress changed" }
                preparationState.value = it.toPresentation()
            }
        }
    }

    private suspend fun failPreparation(attempt: Int) {
        val current = resources.preparation.first().toPresentation()
        if (isReleased || attempt != generation) return
        log.v { "Document preparation failed" }
        preparationState.value = current
        fail(attempt)
    }

    private fun fail(attempt: Int) {
        if (isReleased || attempt != generation) return
        generation++
        initialization?.cancel()
        engine?.release()
        engine = null
        container?.removeAll()
        failure.value = true
        preparationState.value = preparationState.value.failed()
        log.i { "Document view unavailable" }
    }
}
