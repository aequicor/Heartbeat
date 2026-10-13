package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_document_closed
import org.jetbrains.compose.resources.stringResource

/** The page of [surface] in the size it is given, or a note once the document is closed. */
@Composable
internal fun BrowserDocumentScreen(surface: BrowserDocumentSurface?, modifier: Modifier = Modifier) {
    if (surface == null) {
        BrowserDocumentNote(stringResource(Res.string.browser_document_closed), modifier)
    } else {
        BrowserDocumentView(surface, modifier)
    }
}

/** A centred note in place of a page. */
@Composable
internal fun BrowserDocumentNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { HbEmptyState(text) }
}

/** Embeds the page of [surface] on platforms that can isolate it, otherwise explains why it is absent. */
@Composable
internal expect fun BrowserDocumentView(surface: BrowserDocumentSurface, modifier: Modifier = Modifier)
