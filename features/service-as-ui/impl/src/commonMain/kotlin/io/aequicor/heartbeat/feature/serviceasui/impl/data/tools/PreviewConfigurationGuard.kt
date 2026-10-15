package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** Null means trusted settings changed. Caller cancellation propagates and waits for renderer cleanup. */
internal suspend fun previewWithConfiguration(
    configuration: Flow<UiWebConfiguration>,
    expected: UiWebConfiguration,
    render: suspend () -> BrowserDocumentPreviewResult,
): BrowserDocumentPreviewResult? = coroutineScope {
    val ready = CompletableDeferred<Unit>()
    val changed = async(start = CoroutineStart.UNDISPATCHED) {
        configuration.first { current ->
            if (current == expected) ready.complete(Unit)
            current != expected
        }
    }
    try {
        // Observe the current persisted settings before opening any page, including after automatic review.
        val isReady = select {
            changed.onAwait { false }
            ready.onAwait { true }
        }
        if (!isReady) return@coroutineScope null
        val rendered = async { render() }
        select {
            changed.onAwait {
                rendered.cancel()
                null
            }
            rendered.onAwait { it }
        }
    } finally {
        changed.cancel()
    }
}
