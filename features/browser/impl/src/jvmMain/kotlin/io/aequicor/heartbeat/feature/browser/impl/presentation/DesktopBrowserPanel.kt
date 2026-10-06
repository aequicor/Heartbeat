package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.impl.domain.desktopBrowserFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Owns one native surface. Startup suspends while the app runtime extracts on IO, keeping EDT free.
 * Native callbacks are marshalled to EDT and invalidated before disposal. Reload retries failed startup.
 */
internal class DesktopBrowserPanel(
    private val surface: BrowserSurface,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val createRuntime: suspend (() -> Boolean, (BrowserViewState) -> Unit) -> DesktopBrowserRuntime,
) : BrowserViewController {
    private val log = Log.tag("DesktopBrowserPanel")
    private val isReleased = AtomicBoolean(false)
    private var native: DesktopBrowserRuntime? = null
    private var container: JPanel? = null
    private var initialization: Job? = null
    private var isAttached = false

    @Volatile
    private var generation = 0
    private var currentUrl = surface.initialUrl
    private val pending = mutableListOf<BrowserViewCommand>()

    fun createComponent(): JComponent {
        check(SwingUtilities.isEventDispatchThread())
        check(container == null)
        val panel = JPanel(BorderLayout()).apply { isOpaque = false }
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
            if (command is BrowserViewCommand.Load && isBrowserUrlAllowed(command.url)) currentUrl = command.url
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
            initialization = null
            pending.clear()
            if (isAttached) surface.detached(this)
            native?.release()
            native = null
            container?.removeAll()
            container = null
        }
    }

    private fun initialize() {
        val attempt = ++generation
        publish(BrowserViewState(url = currentUrl, isLoading = true))
        initialization = scope.launch(dispatchers.main) {
            try {
                val runtime = createRuntime(
                    { isReleased.get() || attempt != generation },
                    { state -> onSwing { onNativeState(attempt, state) } },
                )
                if (isReleased.get() || attempt != generation) {
                    runtime.release()
                    return@launch
                }
                native = runtime
                container?.apply {
                    add(runtime.component, BorderLayout.CENTER)
                    revalidate()
                    repaint()
                }
                // Pending commands supersede restoration, including Stop during initialization.
                val hasNavigation = pending.any { it is BrowserViewCommand.Load || it == BrowserViewCommand.Stop }
                runtime.start(if (hasNavigation) "" else currentUrl)
                pending.toList().also { pending.clear() }.forEach(runtime::execute)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(error.desktopBrowserFailure()) { "Desktop browser initialization failed" }
                fail()
            } catch (error: LinkageError) {
                log.e(error.desktopBrowserFailure()) { "Desktop browser native runtime is unavailable" }
                fail()
            }
        }
    }

    private fun onNativeState(attempt: Int, state: BrowserViewState) {
        if (attempt != generation || isReleased.get()) return
        if (state.error == BrowserViewError.EngineUnavailable) {
            generation++
            fail()
        } else {
            publish(state)
        }
    }

    private fun fail() {
        native?.release()
        native = null
        container?.removeAll()
        pending.clear()
        publish(BrowserViewState(url = currentUrl, error = BrowserViewError.EngineUnavailable))
    }

    private fun publish(state: BrowserViewState) {
        if (isReleased.get()) return
        if (isBrowserUrlAllowed(state.url)) currentUrl = state.url
        surface.changed(this, state)
    }

    private fun onSwing(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }
}

/** Testable presentation port, independent of the CEF JNI lifetime. */
internal interface DesktopBrowserRuntime : BrowserViewController {
    val component: Component
    fun start(initialUrl: String)
    fun release()
}
