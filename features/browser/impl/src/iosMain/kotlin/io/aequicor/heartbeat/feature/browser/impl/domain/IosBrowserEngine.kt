package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserPage

/** Creates one isolated, surface-owned native browser; callbacks contain main-frame state only. */
internal fun interface IosBrowserEngineFactory {
    fun create(onChanged: (BrowserPage) -> Unit): IosBrowserEngine
}

/** Opaque native view ownership keeps UIKit and Foundation outside the feature's domain contract. */
internal interface IosBrowserEngine {
    val nativeView: Any
    fun execute(command: BrowserCommand)
    fun release()
}
