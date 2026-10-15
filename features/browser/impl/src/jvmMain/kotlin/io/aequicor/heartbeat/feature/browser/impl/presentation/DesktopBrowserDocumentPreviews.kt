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

/** Temporary documents reuse the production renderer and its profile/app teardown; the native peer is never shown. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, priority = 1)
@Inject
internal class DesktopBrowserDocumentPreviews(
    private val documents: BrowserDocuments,
    private val registry: BrowserDocumentRegistry,
    private val natives: BrowserDocumentNatives,
    private val dispatchers: DispatcherProvider,
    private val themes: DesktopDocumentPreviewThemes,
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
            withTimeoutOrNull(RENDER_TIMEOUT_MILLIS) {
                withContext(dispatchers.main) { lease.open(request, documents, registry, panels, themes) }
                val runtime = checkNotNull(lease.panel).awaitPreviewRuntime(dispatchers)
                captureDocumentPreview(runtime, request)
            } ?: failed(BrowserDocumentPreviewFailure.TimedOut)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PreviewImageTooLarge) {
            log.w(error.redactedFailure()) { "Isolated document preview exceeds image limit" }
            failed(BrowserDocumentPreviewFailure.ImageTooLarge)
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Isolated document preview failed" }
            failed(BrowserDocumentPreviewFailure.RenderFailed)
        } catch (error: LinkageError) {
            log.w(error.redactedFailure()) { "Isolated document runtime is unavailable" }
            failed(BrowserDocumentPreviewFailure.RuntimeUnavailable)
        } finally {
            withContext(NonCancellable) {
                // A stalled native close must not admit a second renderer or dispose its native parent prematurely.
                if (lease.close()) {
                    rendering.unlock()
                } else {
                    log.w { "Isolated document preview is waiting for native close" }
                    app.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        withContext(NonCancellable) {
                            lease.finishClosing()
                            rendering.unlock()
                        }
                    }
                }
            }
        }
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

    fun open(
        request: BrowserDocumentPreviewRequest,
        documents: BrowserDocuments,
        registry: BrowserDocumentRegistry,
        panels: DesktopDocumentPanels,
        themes: DesktopDocumentPreviewThemes,
    ) {
        val opened = checkNotNull(
            documents.open(
                BrowserDocument(
                    request.html,
                    "Document preview",
                    request.data,
                    resourcePolicy = request.resourcePolicy,
                ),
            ),
        )
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
    check(prepared == BrowserRuntimeUi.Ready) { "Document runtime is unavailable" }
    val runtime = withContext(dispatchers.main) { runtimeForPreview }
    runtime.awaitLoaded()
    return runtime
}
