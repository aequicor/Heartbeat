package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_document_unsupported
import org.jetbrains.compose.resources.stringResource

/** Isolated documents need the desktop engine's own origins and request handling. */
@Composable
internal actual fun BrowserDocumentView(surface: BrowserDocumentSurface, modifier: Modifier) =
    BrowserDocumentFallback(surface, stringResource(Res.string.browser_document_unsupported), modifier)
