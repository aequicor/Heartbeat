package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.CardFocusRequestUi

/** Reveals the exact card after the transcript mounts its virtualized embedded block, then consumes the request. */
@Composable
internal fun StudioEmbeddedCard(
    card: ComposableComponent,
    focus: CardFocusRequestUi?,
    onFocus: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { BringIntoViewRequester() }
    val latestOnFocus by rememberUpdatedState(onFocus)
    LaunchedEffect(focus) {
        if (focus != null) {
            withFrameNanos { }
            requester.bringIntoView()
            latestOnFocus(focus.sequence)
        }
    }
    Box(modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
        card.Content(Modifier.fillMaxWidth())
    }
}
