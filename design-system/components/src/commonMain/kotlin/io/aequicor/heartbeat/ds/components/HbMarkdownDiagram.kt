package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbScrollbars
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val log = Log.tag("DS/Diagram")

/**
 * One complete diagram fence. With a renderer from [HbDiagramsProvider] it shows the drawn image (click opens a
 * full-size viewer), while drawing the source with a status line, and on errors the message above the source.
 * Without a renderer, or for sources the renderer does not draw, the row shows its source like a code fence.
 * Rendered with Foundation for every platform kit, like the rest of Markdown. [MAX_DIAGRAM_LINES] and
 * [MAX_DIAGRAM_CHARACTERS] keep the source panel bounded.
 */
@Composable
internal fun MarkdownDiagram(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
) {
    val diagrams = currentHbDiagrams
    val language = block.diagram
    if (diagrams == null || language == null) {
        MarkdownDiagramSource(block, modifier, foreground)
        return
    }
    // A different source never shows the previous image; a new theme or density keeps it until redrawn.
    key(language, block.content.text) {
        DrawnDiagram(block, language, diagrams, modifier, foreground)
    }
}

@Composable
private fun DrawnDiagram(
    block: HbMarkdownBlock,
    language: HbDiagramLanguage,
    diagrams: HbDiagrams,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
) {
    val style = rememberDiagramStyle()
    val density = LocalDensity.current.density
    val request = remember(block.content.text, language, style, density) {
        HbDiagramRequest(language, block.content.text, style, density)
    }
    var attempt by remember(request) { mutableIntStateOf(0) }
    val cached = remember(diagrams, request) { diagrams.images.peek(request) }
    val result by produceState<HbDiagramResult?>(cached, diagrams, request, attempt) {
        if (value !is HbDiagramResult.Image) value = null
        value = diagrams.render(request)
    }
    val labels = diagrams.labels
    when (val current = result) {
        null -> MarkdownDiagramSource(block, modifier, foreground, status = labels.rendering)

        is HbDiagramResult.Image -> DiagramPreview(block, current, labels, modifier, foreground)

        is HbDiagramResult.SyntaxError -> DiagramProblem(block, current.message(labels), labels, modifier, foreground)

        is HbDiagramResult.Failed -> DiagramProblem(
            block,
            current.reason.message(labels),
            labels,
            modifier,
            foreground,
            onRetry = if (current.reason.isTransient) {
                { attempt += 1 }
            } else {
                null
            },
        )

        HbDiagramResult.Unsupported -> MarkdownDiagramSource(block, modifier, foreground)
    }
}

/** Renders through the cache; a renderer that breaks its contract and throws becomes an internal failure. */
private suspend fun HbDiagrams.render(request: HbDiagramRequest): HbDiagramResult {
    images.peek(request)?.let { cached ->
        images.put(request, cached)
        return cached
    }
    val result = try {
        renderer.render(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "Diagram renderer failed" }
        HbDiagramResult.Failed(HbDiagramFailure.Internal)
    }
    if (result is HbDiagramResult.Image) images.put(request, result)
    return result
}

@Composable
private fun rememberDiagramStyle(): HbDiagramStyle {
    val colors = HbTheme.colors
    val fontSize = HbTheme.typography.body.fontSize.value * LocalDensity.current.fontScale
    return remember(colors, fontSize) {
        // Shapes sit one step above the elevated panel in both palettes; notes keep the quiet accent container.
        HbDiagramStyle(
            text = colors.textPrimary,
            secondaryText = colors.textSecondary,
            line = colors.outline,
            fill = colors.outlineSubtle,
            border = colors.outline,
            accentFill = colors.primaryContainer,
            fontSize = fontSize,
            isDark = colors.isDark,
        )
    }
}

@Composable
private fun DiagramPreview(
    block: HbMarkdownBlock,
    image: HbDiagramResult.Image,
    labels: HbDiagramLabels,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
) {
    var isViewerOpen by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val shape = HbTheme.shapes.small
    val ratio = (image.size.width / image.size.height).takeIf { it.isFinite() && it > 0f } ?: 1f
    Box(modifier.fillMaxWidth().background(HbTheme.colors.surfaceElevated, shape).padding(HbTheme.spacing.m)) {
        HbTooltip(labels.openFullSize) {
            Image(
                bitmap = image.bitmap,
                contentDescription = labels.diagram,
                modifier = Modifier.testTag(DIAGRAM_PREVIEW_TAG)
                    .widthIn(max = image.size.width)
                    .heightIn(max = minOf(image.size.height, HbTheme.dimensions.diagramPreviewMaxHeight))
                    .aspectRatio(ratio)
                    .hbFocusOutline(isFocused, shape)
                    .clickable(
                        interactions,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = labels.openFullSize,
                    ) {
                        log.i { "Diagram viewer opened" }
                        isViewerOpen = true
                    },
                contentScale = ContentScale.Fit,
                // Fitting a sharp bitmap into the column shrinks it; mipmaps keep thin lines and text legible.
                filterQuality = FilterQuality.Medium,
            )
        }
    }
    if (isViewerOpen) {
        DiagramViewer(block, image, labels, foreground, onDismiss = { isViewerOpen = false })
    }
}

/** Full-size image or its source; both scroll on two axes, by pointer and by keyboard once the viewport is focused. */
@Composable
private fun DiagramViewer(
    block: HbMarkdownBlock,
    image: HbDiagramResult.Image,
    labels: HbDiagramLabels,
    foreground: Color,
    onDismiss: () -> Unit,
) {
    var isSourceShown by remember { mutableStateOf(false) }
    HbDialog(
        title = labels.diagram,
        onDismissRequest = onDismiss,
        width = HbDialogWidth.Wide,
        isContentScrollable = false,
        actions = {
            HbButton(
                if (isSourceShown) labels.showDiagram else labels.showSource,
                {
                    log.i { "Diagram viewer source shown=${!isSourceShown}" }
                    isSourceShown = !isSourceShown
                },
                style = HbButtonStyle.Ghost,
            )
            HbCopyButton(block.content.text, labels.copySource, labels.sourceCopied, labels.copyFailed)
            HbButton(labels.close, onDismiss, style = HbButtonStyle.Secondary)
        },
    ) {
        DiagramViewport {
            if (isSourceShown) {
                SelectionContainer {
                    HbCodeText(
                        text = block.content.text,
                        spans = remember(block.content.text, block.language) {
                            highlightHbCode(block.content.text, block.language)
                        },
                        foreground = foreground,
                    )
                }
            } else {
                Image(image.bitmap, contentDescription = labels.diagram, modifier = Modifier.size(image.size))
            }
        }
    }
}

/**
 * A focusable viewport scrolling on both axes. Its scrollbars sit on the viewport edges rather than on the moving
 * content; arrows, Page Up/Down and Home/End scroll it from the keyboard.
 */
@Composable
private fun DiagramViewport(content: @Composable () -> Unit) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val shape = HbTheme.shapes.small
    val step = with(LocalDensity.current) { HbTheme.dimensions.keyboardScrollStep.toPx() }
    var viewportHeight by remember { mutableIntStateOf(0) }
    Box(
        Modifier.testTag(DIAGRAM_VIEWER_TAG)
            .onSizeChanged { viewportHeight = it.height }
            .hbFocusOutline(isFocused, shape)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val (dx, dy) = when (event.key) {
                    Key.MoveHome -> 0f to -vertical.value.toFloat()
                    Key.MoveEnd -> 0f to (vertical.maxValue - vertical.value).toFloat()
                    else -> event.key.viewportScroll(step, viewportHeight) ?: return@onKeyEvent false
                }
                scope.launch {
                    horizontal.scrollBy(dx)
                    vertical.scrollBy(dy)
                }
                true
            }
            .focusable(interactionSource = interactions)
            .background(HbTheme.colors.surfaceElevated, shape)
            .hbScrollbars(vertical, Orientation.Vertical)
            .hbScrollbars(horizontal, Orientation.Horizontal)
            .verticalScroll(vertical)
            .horizontalScroll(horizontal)
            .padding(HbTheme.spacing.m),
    ) { content() }
}

/** Horizontal and vertical scroll of an arrow or page key, or null for keys the viewport leaves to others. */
private fun Key.viewportScroll(step: Float, page: Int): Pair<Float, Float>? = when (this) {
    Key.DirectionDown -> 0f to step
    Key.DirectionUp -> 0f to -step
    Key.DirectionRight -> step to 0f
    Key.DirectionLeft -> -step to 0f
    Key.PageDown -> 0f to page.toFloat()
    Key.PageUp -> 0f to -page.toFloat()
    else -> null
}

@Composable
private fun DiagramProblem(
    block: HbMarkdownBlock,
    message: String,
    labels: HbDiagramLabels,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
    onRetry: (() -> Unit)? = null,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbBanner(
            message,
            tone = if (onRetry == null) HbTone.Danger else HbTone.Warning,
            actions = {
                if (onRetry != null) {
                    HbButton(labels.retry, onRetry, style = HbButtonStyle.Ghost, size = HbButtonSize.Small)
                }
            },
        )
        MarkdownDiagramSource(block, foreground = foreground)
    }
}

@Composable
private fun MarkdownDiagramSource(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
    status: String? = null,
) {
    val spans = remember(block.content.text, block.language) { highlightHbCode(block.content.text, block.language) }
    MarkdownCodePanel(modifier) {
        if (!block.language.isNullOrBlank() || status != null) {
            HbRow(gap = HbTheme.spacing.s) {
                block.language?.takeIf { it.isNotBlank() }?.let { language ->
                    HbText(text = language, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
                }
                status?.let {
                    HbText(text = it, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
                }
            }
        }
        HbScrollableCode(text = block.content.text, spans = spans, foreground = foreground)
    }
}

private fun HbDiagramResult.SyntaxError.message(labels: HbDiagramLabels): String =
    (line?.let { labels.syntaxErrorAtLine.replace("{line}", it.toString()) } ?: labels.syntaxError)
        .replace("{message}", message)

private fun HbDiagramFailure.message(labels: HbDiagramLabels): String = when (this) {
    HbDiagramFailure.TooLarge -> labels.tooLarge
    HbDiagramFailure.Timeout -> labels.timeout
    HbDiagramFailure.Busy -> labels.busy
    HbDiagramFailure.Internal -> labels.failed
}

private val HbDiagramFailure.isTransient: Boolean
    get() = this == HbDiagramFailure.Timeout || this == HbDiagramFailure.Busy

/** Test tags of the inline image and the full-size viewer. */
internal const val DIAGRAM_PREVIEW_TAG = "diagram-preview"
internal const val DIAGRAM_VIEWER_TAG = "diagram-viewer"
