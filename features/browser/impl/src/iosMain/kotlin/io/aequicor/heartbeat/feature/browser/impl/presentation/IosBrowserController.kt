package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.IosBrowserEngine
import io.aequicor.heartbeat.feature.browser.impl.domain.IosBrowserEngineFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView

/** Platform surface supplied by the iOS composition root without exposing data adapters to UI. */
internal interface IosBrowserSurface : BrowserSurface {
    val engines: IosBrowserEngineFactory
}

/** Owns an opaque native engine for the lifetime of a single UIKit interop view. */
@OptIn(ExperimentalForeignApi::class)
internal class IosBrowserController(private val surface: BrowserSurface) : BrowserViewController {
    private var engine: IosBrowserEngine? = null
    private var isReleased = false
    private var hasReceivedCommand = false

    fun createView(): UIView {
        val native = requireNotNull(surface as? IosBrowserSurface) { "Missing iOS browser surface binding" }
            .engines.create(::changed)
        engine = native
        surface.attached(this)
        if (!hasReceivedCommand && surface.initialUrl.isNotEmpty()) {
            execute(BrowserViewCommand.Load(surface.initialUrl))
        } else if (!hasReceivedCommand) {
            surface.changed(this, BrowserViewState())
        }
        return native.nativeView as UIView
    }

    override fun execute(command: BrowserViewCommand) {
        if (isReleased) return
        hasReceivedCommand = true
        engine?.execute(
            when (command) {
                is BrowserViewCommand.Load -> BrowserCommand.Load(command.url)
                BrowserViewCommand.Back -> BrowserCommand.Back
                BrowserViewCommand.Forward -> BrowserCommand.Forward
                BrowserViewCommand.Reload -> BrowserCommand.Reload
                BrowserViewCommand.Stop -> BrowserCommand.Stop
            },
        )
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        surface.detached(this)
        engine?.release()
        engine = null
    }

    @HighFrequency
    private fun changed(page: BrowserPage) {
        if (isReleased) return
        surface.changed(
            this,
            BrowserViewState(
                url = page.url,
                title = page.title,
                isLoading = page.isLoading,
                isBackAvailable = page.isBackAvailable,
                isForwardAvailable = page.isForwardAvailable,
                error = when (page.error) {
                    null -> null
                    BrowserError.LoadFailed -> BrowserViewError.LoadFailed
                    BrowserError.EngineUnavailable -> BrowserViewError.EngineUnavailable
                    BrowserError.InvalidAddress, BrowserError.UnsupportedAddress -> BrowserViewError.UnsupportedAddress
                },
            ),
        )
    }
}
