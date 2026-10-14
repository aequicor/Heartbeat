package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/Dialog")

/** Upper width bound of [HbDialog]: [Regular] for forms and confirmations, [Wide] for content that needs room. */
public enum class HbDialogWidth {
    Regular,
    Wide,
}

/**
 * The one modal surface of the design system: a dialog on wide windows and a full-width sheet on compact ones.
 * A [title], scrollable [content] and [actions] aligned to the trailing edge (primary action last). Esc, the
 * system back action and a click outside call [onDismissRequest]; the caller decides whether to close.
 * The surface is flat with the popup shadow — the only elevated surface of the style — and no blur.
 * With [isContentScrollable] false the content area is still bounded by the window but does not scroll itself,
 * so content with its own scrolling (for example on both axes) is not nested inside a vertical scroll.
 * [isContentFill] gives an application workspace all available height. Its fixed [headerActions] remain reachable
 * while the body scrolls. [isTitleVisible] may hide a title already drawn by the document; the accessible pane name
 * stays available. [isCompact] uses workspace insets independently of height; an absent [actions] slot reserves
 * no footer space. [isContentEdgeToEdge] removes the inner chrome and padding, placing [headerActions] in a
 * non-focusable top-end overlay above native content. [title] remains accessible; [LocalHbDialogOverlayInsets]
 * tells the content which corner its own controls must avoid. The outer window margin and shape remain unchanged.
 */
@Composable
public fun HbDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    width: HbDialogWidth = HbDialogWidth.Regular,
    isContentScrollable: Boolean = true,
    isContentFill: Boolean = false,
    isTitleVisible: Boolean = true,
    isCompact: Boolean = isContentFill,
    isContentEdgeToEdge: Boolean = false,
    headerActions: @Composable RowScope.() -> Unit = {},
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val dimensions = HbTheme.dimensions
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val isSheet = windowWidth < dimensions.compactBreakpoint
    val maxWidth = dialogMaxWidth(width, windowWidth)
    val focus = remember { FocusRequester() }
    val inset = if (isCompact) HbTheme.spacing.s else HbTheme.spacing.xl
    val gap = if (isCompact) HbTheme.spacing.s else HbTheme.spacing.l
    LaunchedEffect(focus) {
        // Wait until the focus target is attached and laid out.
        withFrameNanos { }
        focus.requestFocus()
    }
    HbPlatformDialog(
        onDismissRequest = {
            log.i { "dialog dismissed" }
            dismiss()
        },
    ) {
        Box(
            modifier
                .then(if (isSheet) Modifier.fillMaxWidth() else Modifier)
                .widthIn(min = minOf(dimensions.dialogMinWidth, maxWidth), max = maxWidth)
                .then(if (isContentFill) Modifier.fillMaxSize() else Modifier)
                .padding(HbTheme.spacing.l)
                .hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.large)
                .semantics { paneTitle = title }
                .dismissDialogOnEscape(dismiss)
                // The panel takes focus on open so Esc and Tab work before any control is focused.
                .focusRequester(focus)
                .focusable(),
            propagateMinConstraints = true,
        ) {
            if (isContentEdgeToEdge) {
                EdgeToEdgeDialogBody(isContentScrollable, isContentFill, headerActions, actions, content)
            } else {
                CompositionLocalProvider(LocalHbDialogOverlayInsets provides HbDialogOverlayInsets()) {
                    HbColumn(Modifier.padding(inset), gap = gap) {
                        DialogHeader(title, isTitleVisible, headerActions)
                        DialogBody(isContentScrollable, isContentFill, content = content)
                        DialogActions(actions)
                    }
                }
            }
        }
    }
}

private fun Modifier.dismissDialogOnEscape(onDismissRequest: () -> Unit): Modifier = onPreviewKeyEvent {
    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
        log.i { "dialog dismissed with Escape" }
        onDismissRequest()
        true
    } else {
        false
    }
}

/** A small separate window keeps the controls above heavyweight children without covering the rest of the page. */
@Composable
private fun EdgeToEdgeDialogBody(
    isScrollable: Boolean,
    isFill: Boolean,
    headerActions: @Composable RowScope.() -> Unit,
    actions: (@Composable RowScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val inset = HbTheme.spacing.s
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val occupied = with(density) {
        if (overlaySize == IntSize.Zero) {
            HbDialogOverlayInsets()
        } else {
            HbDialogOverlayInsets(overlaySize.width.toDp() + inset, overlaySize.height.toDp() + inset)
        }
    }
    Box(Modifier.clip(HbTheme.shapes.large), propagateMinConstraints = true) {
        CompositionLocalProvider(LocalHbDialogOverlayInsets provides occupied) {
            HbColumn(Modifier.fillMaxWidth().heightIn(min = occupied.height), gap = 0.dp) {
                DialogBody(isScrollable, isFill, gap = 0.dp, content = content)
                DialogActions(actions)
            }
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
            HbRow(Modifier.onSizeChanged { overlaySize = it }, gap = HbTheme.spacing.s, content = headerActions)
        }
    }
}

@Composable
private fun ColumnScope.DialogBody(
    isScrollable: Boolean,
    isFill: Boolean,
    gap: Dp = HbTheme.spacing.m,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body = Modifier.weight(1f, fill = isFill)
    HbColumn(
        if (isScrollable) body.hbVerticalScroll(rememberScrollState()) else body,
        gap = gap,
        content = content,
    )
}

@Composable
private fun ColumnScope.DialogActions(actions: (@Composable RowScope.() -> Unit)?) {
    if (actions != null) {
        HbRow(Modifier.align(Alignment.End), gap = HbTheme.spacing.s, content = actions)
    }
}

@Composable
@ReadOnlyComposable
private fun dialogMaxWidth(width: HbDialogWidth, windowWidth: Dp): Dp = when {
    windowWidth < HbTheme.dimensions.compactBreakpoint -> windowWidth
    width == HbDialogWidth.Wide -> HbTheme.dimensions.dialogWideMaxWidth
    else -> HbTheme.dimensions.dialogMaxWidth
}

@Composable
private fun DialogHeader(title: String, isTitleVisible: Boolean, actions: @Composable RowScope.() -> Unit) {
    HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
        if (isTitleVisible) {
            HbText(
                title,
                Modifier.weight(1f).semantics { heading() },
                style = HbTheme.typography.title.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** The platform owns modal input interception and dimming above any embedded native surface. */
@Composable
internal expect fun HbPlatformDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit)
