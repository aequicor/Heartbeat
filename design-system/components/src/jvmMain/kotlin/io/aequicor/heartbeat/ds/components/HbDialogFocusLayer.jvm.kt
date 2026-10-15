package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

/** The modal owns focus and accessibility; closing restores the background and its prior focused child. */
@Composable
internal fun HbDialogFocusLayer(
    isActive: Boolean,
    background: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit,
) {
    val backgroundFocus = remember { FocusRequester() }
    var isPreviouslyActive by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (!isActive && isPreviouslyActive) {
            withFrameNanos { }
            backgroundFocus.requestFocus()
        }
        isPreviouslyActive = isActive
    }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().semantics { if (isActive) hideFromAccessibility() }
                .focusRequester(backgroundFocus).focusRestorer().focusGroup(),
        ) {
            background()
        }
        if (isActive) {
            Box(
                Modifier.fillMaxSize().semantics { dialog() }
                    .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            ) {
                overlay()
            }
        }
    }
}
