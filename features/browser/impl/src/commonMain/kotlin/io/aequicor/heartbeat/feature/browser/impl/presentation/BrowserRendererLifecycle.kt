package io.aequicor.heartbeat.feature.browser.impl.presentation

/**
 * UI-thread lifetime of a replaceable native renderer. A crash destroys the native view immediately,
 * but keeps its controller attached until explicit navigation requests a replacement composition.
 */
internal class BrowserRendererLifecycle(
    private val onFailure: () -> Unit,
    private val onDestroy: (isRendererGone: Boolean) -> Unit,
    private val onDetach: () -> Unit,
    private val onRecreate: () -> Unit,
) {
    private var isRendererGone = false
    private var isReleased = false
    private var isRecreationRequested = false

    val isActive: Boolean get() = !isRendererGone && !isReleased

    fun rendererGone() {
        if (!isActive) return
        isRendererGone = true
        onFailure()
        onDestroy(true)
    }

    /** Only explicit load/reload can recover; disabling or detaching sends Stop and must stay inert. */
    fun retry(command: BrowserViewCommand) {
        if (isReleased || !isRendererGone || isRecreationRequested) return
        if (command !is BrowserViewCommand.Load && command != BrowserViewCommand.Reload) return
        isRecreationRequested = true
        onRecreate()
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        onDetach()
        if (!isRendererGone) onDestroy(false)
    }
}
