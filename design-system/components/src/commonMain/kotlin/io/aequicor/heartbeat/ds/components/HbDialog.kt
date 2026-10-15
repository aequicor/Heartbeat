package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * [isInWindow] uses the current desktop window's modal host when available, independently of content padding.
 * [overlayCloseLabel] replaces edge-to-edge [headerActions] with one Close action, using [onDismissRequest] and
 * a keyboard/accessibility target in the dialog's focus tree. Ordinary dialog headers are unchanged.
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
    isInWindow: Boolean = isContentEdgeToEdge,
    headerActions: @Composable RowScope.() -> Unit = {},
    actions: (@Composable RowScope.() -> Unit)? = null,
    overlayCloseLabel: String? = null,
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
    HbPlatformDialog(
        isInWindow = isInWindow,
        onDismissRequest = {
            log.i { "dialog dismissed" }
            dismiss()
        },
    ) {
        LaunchedEffect(focus) {
            // The desktop overlay composes separately; wait for its own focus target and first layout.
            withFrameNanos { }
            focus.requestFocus()
        }
        Box(
            modifier
                .then(if (isSheet) Modifier.fillMaxWidth() else Modifier)
                .widthIn(min = minOf(dimensions.dialogMinWidth, maxWidth), max = maxWidth)
                .then(if (isContentFill) Modifier.fillMaxSize() else Modifier)
                .padding(HbTheme.spacing.l)
                .dialogSurface(isContentEdgeToEdge)
                .semantics { paneTitle = title }
                .dismissDialogOnEscape(dismiss)
                // The panel takes focus on open so Esc and Tab work before any control is focused.
                .focusRequester(focus)
                .focusable(),
            propagateMinConstraints = true,
        ) {
            if (isContentEdgeToEdge) {
                EdgeToEdgeDialogBody(
                    isContentScrollable,
                    isContentFill,
                    headerActions,
                    actions,
                    overlayCloseLabel,
                    dismiss,
                    content,
                )
            } else {
                CompositionLocalProvider(
                    LocalHbDialogOverlayInsets provides HbDialogOverlayInsets(),
                    LocalHbDocumentFocusExit provides null,
                ) {
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

/** Web content keeps only the rounded edge; a punched interop region cannot preserve a host drop shadow. */
@Composable
@ReadOnlyComposable
private fun Modifier.dialogSurface(isEdgeToEdge: Boolean): Modifier = if (isEdgeToEdge) {
    hbSurface(HbTheme.colors.surface, HbTheme.shapes.large, isQuiet = true)
} else {
    hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.large)
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

/** Documents share the same measured control overlay on the start surface and in the modal. */
// Exactly one overlay branch mounts the body; a single action adds a host focus target above native content.
@Suppress("ContentSlotReused")
@Composable
private fun EdgeToEdgeDialogBody(
    isScrollable: Boolean,
    isFill: Boolean,
    headerActions: @Composable RowScope.() -> Unit,
    actions: (@Composable RowScope.() -> Unit)?,
    closeLabel: String?,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable (Modifier) -> Unit = { size ->
        HbColumn(size.fillMaxWidth(), gap = 0.dp) {
            DialogBody(isScrollable, isFill, gap = 0.dp, content = content)
            DialogActions(actions)
        }
    }
    val modifier = Modifier.clip(HbTheme.shapes.large)
    if (closeLabel != null) {
        HbDocumentOverlay(HbIcons.Close, closeLabel, onDismissRequest, modifier, body)
    } else {
        CompositionLocalProvider(LocalHbDocumentFocusExit provides null) {
            HbDocumentOverlay(headerActions, modifier, body)
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
internal expect fun HbPlatformDialog(
    onDismissRequest: () -> Unit,
    isInWindow: Boolean = false,
    content: @Composable () -> Unit,
)
