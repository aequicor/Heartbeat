package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Space occupied by a document's top-end controls, including their inset from those two edges. These are logical
 * dimensions of a corner exclusion, not padding for the whole page. Content may reserve them in its own header.
 */
@Immutable
public data class HbDialogOverlayInsets(val width: Dp = 0.dp, val height: Dp = 0.dp)

/** The nearest document overlay's corner exclusion; ordinary content and dialogs reserve no overlay. */
public val LocalHbDialogOverlayInsets = compositionLocalOf { HbDialogOverlayInsets() }
