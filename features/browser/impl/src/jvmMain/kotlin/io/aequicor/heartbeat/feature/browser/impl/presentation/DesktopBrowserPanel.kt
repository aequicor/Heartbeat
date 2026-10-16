package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.api.BROWSER_DEFAULT_ZOOM
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimePreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Owns one native surface. Startup suspends while the app runtime downloads and extracts on IO, keeping EDT free.
 * Native callbacks are marshalled to EDT and invalidated before disposal. Reload retries failed
 * startup.
 */
internal class DesktopBrowserPanel(
    private val surface: BrowserSurface,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val runtimePreparation: Flow<BrowserRuntimePreparation> = flowOf(BrowserRuntimePreparation.Ready),
    private val createRuntime: suspend (() -> Boolean, (BrowserViewState) -> Unit) -> DesktopBrowserRuntime,
) : BrowserViewController {
    private val log = Log.tag("DesktopBrowserPanel")
    private val isReleased = AtomicBoolean(false)
    private var native: DesktopBrowserRuntime? = null
    private var zoom = ZoomSetting(isEnabled = false, percent = BROWSER_DEFAULT_ZOOM, url = "")
    private val runtimes = mutableListOf<DesktopBrowserRuntime>()
    private val ready = MutableStateFlow<DesktopBrowserRuntime?>(null)
    private val preparationState = MutableStateFlow<BrowserRuntimeUi>(BrowserRuntimeUi.Preparing)

    /** Bootstrap progress belongs to this viewport; ordinary page loading is shown by the tab. */
    val preparation = preparationState.asStateFlow()

    /** Retains the live setting while Chromium initializes or recovers, without starting a hidden tab. */
    fun setZoom(isEnabled: Boolean, percent: Int, url: String) = onSwing {
        val next = ZoomSetting(isEnabled, percent, url)
        if (next != zoom) {
            zoom = next
            native?.setZoom(isEnabled, percent, url)
        }
    }

    /** A timeout fails the call with IllegalStateException; only the caller's own cancellation cancels it. */
    suspend fun devTools(method: String, arguments: JsonObject): String =
        availableRuntime("Browser automation call", DEVTOOLS_TIMEOUT) {
            it.devTools(method, arguments)
        }

    /** A timeout fails the capture with IllegalStateException; only the caller's own cancellation cancels it. */
    suspend fun captureResource(url: String): JsonObject =
        availableRuntime("Browser resource capture", RESOURCE_TIMEOUT) {
            it.captureResource(url)
        }

    /** The site icon [url] fetched through this tab's profile; fails when it is not a small image. */
    suspend fun loadIcon(url: String): ByteArray = availableRuntime("Site icon", ICON_TIMEOUT) { it.loadIcon(url) }

    private suspend fun <T : Any> availableRuntime(
        operation: String,
        timeout: Long,
        action: suspend (DesktopBrowserRuntime) -> T,
    ): T = withFailureTimeout(timeout, log, operation) {
        val (runtime, status) = combine(ready, preparation) { runtime, status -> runtime to status }.first {
            it.first != null || it.second is BrowserRuntimeUi.Downloading ||
                it.second == BrowserRuntimeUi.Installing || it.second is BrowserRuntimeUi.Failed
        }
        checkNotNull(runtime) {
            if (status is BrowserRuntimeUi.Failed) {
                "Browser components are unavailable; retry setup in the browser"
            } else {
                "Browser components are being prepared; retry after setup finishes"
            }
        }
        action(runtime)
    }

    private companion object {
        const val DEVTOOLS_TIMEOUT = 30_000L
        const val RESOURCE_TIMEOUT = 60_000L
        const val ICON_TIMEOUT = 15_000L
    }

    private var container: JPanel? = null
    private var initialization: Job? = null
    private var isAttached = false

    @Volatile
    private var generation = 0
    private var currentUrl = surface.initialUrl
    private val pending = mutableListOf<BrowserViewCommand>()

    /** Starts preparation before mounting Swing, so its heavyweight background cannot hide the Compose status. */
    fun prepare() = onSwing { createComponent() }

    /** A stale retry control must not reload a page whose preparation already succeeded. */
    fun retry() = onSwing {
        if (preparation.value is BrowserRuntimeUi.Failed) execute(BrowserViewCommand.Reload)
    }

    fun createComponent(): JComponent {
        check(SwingUtilities.isEventDispatchThread())
        container?.let {
            return it
        }
        val panel = DesktopBrowserContainer()
        container = panel
        if (!isReleased.get()) {
            isAttached = true
            surface.attached(this)
            initialize()
        }
        return panel
    }

    override fun execute(command: BrowserViewCommand) {
        onSwing {
            if (isReleased.get()) return@onSwing
            if (command is BrowserViewCommand.Load && isBrowserUrlAllowed(command.url)) {
                currentUrl = command.url
            }
            val runtime = native
            if (runtime != null) {
                runtime.execute(command)
            } else if (command == BrowserViewCommand.Stop) {
                pending.clear()
                pending += command
                publish(BrowserViewState(url = currentUrl))
            } else {
                pending += command
                if (initialization?.isActive != true) initialize()
            }
        }
    }

    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        onSwing {
            generation++
            initialization?.cancel()
            pending.clear()
            if (isAttached) surface.detached(this)
            native?.release()
            native = null
            log.v { "Browser native runtime detached" }
            ready.value = null
            container?.removeAll()
            container = null
        }
    }

    /** Waits for startup cancellation and every native close callback, including a failed startup's runtime. */
    suspend fun awaitClosed() {
        release()
        val starting = withContext(dispatchers.main) { initialization }
        starting?.join()
        val retired = withContext(dispatchers.main) { runtimes.toList() }
        retired.forEach { it.awaitClosed() }
        withContext(dispatchers.main) { runtimes.removeAll(retired.toSet()) }
    }

    private fun initialize() {
        val attempt = ++generation
        preparationState.value = BrowserRuntimeUi.Preparing
        publish(BrowserViewState(url = currentUrl, isLoading = true))
        initialization = scope.launch(dispatchers.main) {
            val progress = observePreparation(attempt)
            try {
                val runtime = createRuntime(
                    { isReleased.get() || attempt != generation },
                    { state -> onSwing { onNativeState(attempt, state) } },
                )
                runtimes += runtime
                if (isReleased.get() || attempt != generation) {
                    runtime.release()
                    return@launch
                }
                native = runtime
                ready.value = runtime
                preparationState.value = BrowserRuntimeUi.Ready
                container?.apply {
                    add(runtime.component, BorderLayout.CENTER)
                    revalidate()
                    repaint()
                }
                // Pending commands supersede restoration, including Stop during initialization.
                val hasNavigation = pending.any { it is BrowserViewCommand.Load || it == BrowserViewCommand.Stop }
                runtime.setZoom(zoom.isEnabled, zoom.percent, zoom.url)
                runtime.start(if (hasNavigation) "" else currentUrl)
                pending.toList().also { pending.clear() }.forEach(runtime::execute)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(error.redactedFailure()) { "Desktop browser initialization failed" }
                failPreparation(attempt)
            } catch (error: LinkageError) {
                log.e(error.redactedFailure()) { "Desktop browser native runtime is unavailable" }
                failPreparation(attempt)
            } finally {
                progress.cancel()
            }
        }
    }

    @HighFrequency
    private fun observePreparation(attempt: Int): Job = scope.launch(dispatchers.main) {
        runtimePreparation.collect {
            if (!isReleased.get() && attempt == generation && native == null) {
                log.v { "Browser preparation progress changed" }
                preparationState.value = it.toPresentation()
            }
        }
    }

    private fun onNativeState(attempt: Int, state: BrowserViewState) {
        if (attempt != generation || isReleased.get()) return
        if (state.error == BrowserViewError.EngineUnavailable) {
            fail(attempt)
        } else {
            publish(state)
        }
    }

    private suspend fun failPreparation(attempt: Int) {
        val current = runtimePreparation.first().toPresentation()
        if (attempt != generation || isReleased.get()) return
        log.v { "Browser preparation failed" }
        preparationState.value = current
        fail(attempt)
    }

    private fun fail(attempt: Int) {
        if (attempt != generation || isReleased.get()) return
        generation++
        initialization?.cancel()
        native?.release()
        native = null
        log.v { "Browser native runtime detached" }
        ready.value = null
        preparationState.value = preparationState.value.failed()
        container?.removeAll()
        pending.clear()
        publish(BrowserViewState(url = currentUrl, error = BrowserViewError.EngineUnavailable))
    }

    @HighFrequency
    private fun publish(state: BrowserViewState) {
        if (isReleased.get()) return
        log.v { "Browser page projection changed" }
        if (isBrowserUrlAllowed(state.url)) currentUrl = state.url
        surface.changed(this, state)
    }

    private fun onSwing(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }
}

/** The latest zoom setting, reapplied to a runtime created after it changed. */
private data class ZoomSetting(val isEnabled: Boolean, val percent: Int, val url: String)

/** Testable presentation port, independent of the CEF JNI lifetime. */
internal interface DesktopBrowserRuntime : BrowserViewController {
    /** Live zoom settings; the real engine reapplies them after a main-frame navigation. */
    fun setZoom(isEnabled: Boolean, percent: Int, url: String) = Unit

    suspend fun devTools(method: String, arguments: JsonObject): String =
        throw UnsupportedOperationException("Browser automation is unavailable")

    suspend fun captureResource(url: String): JsonObject =
        throw UnsupportedOperationException("Browser resource loading is unavailable")

    suspend fun loadIcon(url: String): ByteArray =
        throw UnsupportedOperationException("Browser icon loading is unavailable")

    val component: Component
    fun start(initialUrl: String)
    fun release()
    suspend fun awaitClosed() = Unit
}
