package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentNatives
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentRegistry
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.domain.isValidPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.swing.JWindow

/**
 * Temporary documents reuse the production renderer and its profile/app teardown; the native peer is never shown.
 * A preview never starts the first runtime download: that needs the progress and retry UI of a visible document.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, priority = 1)
@Inject
internal class DesktopBrowserDocumentPreviews(
    private val documents: BrowserDocuments,
    private val registry: BrowserDocumentRegistry,
    private val natives: BrowserDocumentNatives,
    private val dispatchers: DispatcherProvider,
    private val themes: DesktopDocumentPreviewThemes,
    private val resources: DesktopBrowserResources,
    @ForScope(AppScope::class) private val app: ScopeHandle,
) : BrowserDocumentPreviews {
    private val log = Log.tag("BrowserDocumentPreviews")
    private val rendering = Mutex()

    override suspend fun preview(request: BrowserDocumentPreviewRequest): BrowserDocumentPreviewResult {
        if (!request.isValidPreview()) return failed(BrowserDocumentPreviewFailure.InvalidRequest)
        val panels = natives as? DesktopDocumentPanels ?: return BrowserDocumentPreviewResult.Unavailable
        if (!rendering.tryLock()) return failed(BrowserDocumentPreviewFailure.Busy)
        val lease = PreviewLease(dispatchers)
        return try {
            renderSafely(request, panels, lease)
        } finally {
            withContext(NonCancellable) { release(lease) }
        }
    }

    private suspend fun renderSafely(
        request: BrowserDocumentPreviewRequest,
        panels: DesktopDocumentPanels,
        lease: PreviewLease,
    ): BrowserDocumentPreviewResult = try {
        if (resources.requiresRuntimeDownload()) {
            log.i { "Isolated document preview refused: browser component is not installed" }
            failed(BrowserDocumentPreviewFailure.RuntimeUnavailable)
        } else {
            render(request, panels, lease)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: PreviewRuntimeUnavailable) {
        log.w(error.redactedFailure()) { "Isolated document runtime preparation failed" }
        failed(BrowserDocumentPreviewFailure.RuntimeUnavailable)
    } catch (error: PreviewImageTooLarge) {
        log.w(error.redactedFailure()) { "Isolated document preview exceeds image limit" }
        failed(BrowserDocumentPreviewFailure.ImageTooLarge)
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Isolated document preview failed" }
        failed(BrowserDocumentPreviewFailure.RenderFailed)
    } catch (error: LinkageError) {
        log.w(error.redactedFailure()) { "Isolated document runtime is unavailable" }
        failed(BrowserDocumentPreviewFailure.RuntimeUnavailable)
    }

    private suspend fun render(
        request: BrowserDocumentPreviewRequest,
        panels: DesktopDocumentPanels,
        lease: PreviewLease,
    ): BrowserDocumentPreviewResult = withTimeoutOrNull(RENDER_TIMEOUT_MILLIS) {
        val isOpened = withContext(dispatchers.main) { lease.open(request, documents, registry, panels, themes) }
        if (isOpened) {
            val runtime = checkNotNull(lease.panel).awaitPreviewRuntime(dispatchers)
            captureDocumentPreview(runtime, request)
        } else {
            log.w { "Isolated document preview refused: profile document limit reached" }
            failed(BrowserDocumentPreviewFailure.Busy)
        }
    } ?: failed(BrowserDocumentPreviewFailure.TimedOut).also { log.w { "Isolated document preview timed out" } }

    /** A stalled native close must not admit a second renderer or dispose its native parent prematurely. */
    private suspend fun release(lease: PreviewLease) {
        if (!closeOrUnlock(lease)) return
        log.w { "Isolated document preview is waiting for native close" }
        app.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    lease.finishClosing()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error.redactedFailure()) { "Isolated document preview native close failed" }
                } finally {
                    rendering.unlock()
                }
            }
        }
    }

    /**
     * Closes the lease and releases the renderer unless native close is still pending (true). A failing close
     * still releases it: the profile must not stay Busy until restart.
     */
    private suspend fun closeOrUnlock(lease: PreviewLease): Boolean {
        var isPending = false
        try {
            isPending = !lease.close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error.redactedFailure()) { "Isolated document preview close failed" }
            lease.disposePeer()
        } finally {
            if (!isPending) rendering.unlock()
        }
        return isPending
    }

    private fun failed(reason: BrowserDocumentPreviewFailure) = BrowserDocumentPreviewResult.Failure(reason)

    private companion object {
        const val RENDER_TIMEOUT_MILLIS = 20_000L
    }
}

/** Owns partially created resources, including cancellation between registration and native creation. */
private class PreviewLease(private val dispatchers: DispatcherProvider) {
    private var handle: BrowserDocumentHandle? = null
    var panel: DesktopDocumentPanel? = null
        private set
    private var peer: JWindow? = null

    /** False when the profile already holds its maximum number of documents. */
    fun open(
        request: BrowserDocumentPreviewRequest,
        documents: BrowserDocuments,
        registry: BrowserDocumentRegistry,
        panels: DesktopDocumentPanels,
        themes: DesktopDocumentPreviewThemes,
    ): Boolean {
        val opened = documents.open(
            BrowserDocument(
                request.html,
                "Document preview",
                request.data,
                resourcePolicy = request.resourcePolicy,
            ),
        ) ?: return false
        handle = opened
        val view = checkNotNull(panels.retain(registry.documents.value.getValue(opened.route.id)))
        panel = view
        view.updateTheme(themes.create(request.isDark, request.fontScale))
        val window = JWindow()
        peer = window
        window.apply {
            window.focusableWindowState = false
            window.isAutoRequestFocus = false
            window.add(view.createComponent())
            window.setSize(request.width, request.height)
            // Realize only the native peer. Showing a window, enabling global OSR and capturing the OS are unnecessary.
            window.addNotify()
            window.validate()
        }
        return true
    }

    suspend fun close(): Boolean = withContext(NonCancellable) {
        withContext(dispatchers.main) {
            handle?.close()
            panel?.release()
        }
        withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) {
            finishClosing()
            true
        } ?: false
    }

    suspend fun finishClosing() {
        panel?.awaitClosed()
        disposePeer()
    }

    suspend fun disposePeer() {
        withContext(dispatchers.main) { peer?.dispose() }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}

/** Theme port supplied by the UI composition root; the renderer consumes CSS tokens only. */
internal fun interface DesktopDocumentPreviewThemes {
    fun create(isDark: Boolean, fontScale: Float): DesktopDocumentTheme
}

/** Preparation and native loading are separate barriers; both are bounded by the caller's render deadline. */
private suspend fun DesktopDocumentPanel.awaitPreviewRuntime(dispatchers: DispatcherProvider): DesktopDocumentEngine {
    val prepared = preparation.first { it == BrowserRuntimeUi.Ready || it is BrowserRuntimeUi.Failed }
    if (prepared != BrowserRuntimeUi.Ready) throw PreviewRuntimeUnavailable()
    val runtime = withContext(dispatchers.main) { runtimeForPreview }
    runtime.awaitLoaded()
    return runtime
}

private class PreviewRuntimeUnavailable : IllegalStateException("Document runtime is unavailable")
