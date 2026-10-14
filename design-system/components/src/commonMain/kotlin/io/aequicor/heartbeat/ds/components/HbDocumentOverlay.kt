package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Positions [controls] over a document's top-end corner without reserving a header row. Foundation Popup keeps
 * them above native web peers on desktop and supplies the same geometry on other hosts. The content receives
 * its measured corner exclusion through [LocalHbDialogOverlayInsets], plus a minimum height for the controls.
 * The caller owns the frame's surface and clipping. Background controls hide while another modal owns input.
 */
@Composable
@NonRestartableComposable
public fun HbDocumentOverlay(
    controls: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) = DocumentOverlay(controls, modifier, content = content)

/**
 * Single document action with its keyboard and accessibility target in the host's focus tree. Its nonfocusable
 * popup only paints and handles pointer input, so Tab can reach the action without moving native window focus.
 * The visible control mirrors the host target's focus ring; page keystrokes are never intercepted by the frame.
 */
@Composable
public fun HbDocumentOverlay(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val active = LocalHbActiveNativeModalOwner.current
    val owner = LocalHbNativeModalOwner.current
    val isAvailable by rememberUpdatedState(active == null || active === owner)
    val focusExit: () -> Unit = remember(focus) {
        {
            if (isAvailable) focus.requestFocus()
        }
    }
    val inputMode = LocalInputModeManager.current
    val interactions = remember { MutableInteractionSource() }
    DocumentOverlay(
        controls = {
            CompositionLocalProvider(LocalInputModeManager provides inputMode) {
                HbIconButton(
                    icon,
                    contentDescription,
                    onClick,
                    Modifier.padding(HbTheme.spacing.xs).clearAndSetSemantics { }
                        .focusProperties { canFocus = false }
                        .hbFocusOutline(isFocused, HbTheme.shapes.small)
                        .hbSurface(HbTheme.colors.surface, HbTheme.shapes.small),
                )
            }
        },
        modifier = modifier,
        proxy = { target ->
            Box(
                target.focusRequester(focus).onFocusChanged { isFocused = it.isFocused }
                    .semantics { this.contentDescription = contentDescription }
                    .clickable(interactions, indication = null, role = Role.Button, onClick = onClick),
            )
        },
        content = { size ->
            CompositionLocalProvider(LocalHbDocumentFocusExit provides focusExit.takeIf { isAvailable }) {
                content(size)
            }
        },
    )
}

@Composable
private fun DocumentOverlay(
    controls: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    proxy: @Composable (Modifier) -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val inset = HbTheme.spacing.s
    val active = LocalHbActiveNativeModalOwner.current
    val owner = LocalHbNativeModalOwner.current
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val occupied = with(density) {
        if (overlaySize == IntSize.Zero) {
            HbDialogOverlayInsets()
        } else {
            HbDialogOverlayInsets(overlaySize.width.toDp() + inset, overlaySize.height.toDp() + inset)
        }
    }
    Box(modifier, propagateMinConstraints = true) {
        CompositionLocalProvider(LocalHbDialogOverlayInsets provides occupied) {
            content(Modifier.heightIn(min = occupied.height))
        }
        if (active == null || active === owner) {
            Box(Modifier.matchParentSize()) {
                proxy(
                    Modifier.align(Alignment.TopEnd).offset(x = -inset, y = inset)
                        .size(with(density) { overlaySize.width.toDp() }, with(density) { overlaySize.height.toDp() }),
                )
            }
            val insetPx = with(density) { inset.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(-insetPx, insetPx),
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
            ) {
                HbRow(Modifier.onSizeChanged { overlaySize = it }, gap = HbTheme.spacing.s, content = controls)
            }
        }
    }
}
