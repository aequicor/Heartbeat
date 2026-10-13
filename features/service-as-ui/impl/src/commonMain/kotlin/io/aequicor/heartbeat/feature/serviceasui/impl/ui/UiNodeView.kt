package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbMarkdownBlockContent
import io.aequicor.heartbeat.ds.components.HbPressableRow
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.parseHbMarkdown
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.TextStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_more
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_web_unavailable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource

/** Draws one resolved node; presses and inputs go to [onIntent], `web` nodes draw [webs] by node key. */
@Composable
internal fun UiNodeView(
    node: UiNodeUi,
    onIntent: (SurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    webs: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    when (node) {
        is UiNodeUi.Layout -> LayoutView(node, onIntent, modifier, webs)
        is UiNodeUi.Content -> ContentView(node, onIntent, modifier, webs)
        is UiNodeUi.Input -> InputView(node, onIntent, modifier)
    }
}

@Composable
private fun LayoutView(
    node: UiNodeUi.Layout,
    onIntent: (SurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    webs: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    when (node) {
        is UiNodeUi.Column -> HbColumn(modifier.fillMaxWidth(), gap = node.gap.spacing()) {
            Children(node.children, onIntent, webs)
        }

        is UiNodeUi.Row -> if (node.isWrapped) {
            HbFlowRow(modifier, gap = node.gap.spacing()) { Children(node.children, onIntent, webs) }
        } else {
            HbRow(modifier, gap = node.gap.spacing()) { RowChildren(node.children, onIntent, webs) }
        }

        is UiNodeUi.Card -> HbCard(modifier.fillMaxWidth(), contentPadding = HbTheme.spacing.l) {
            HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
                node.title?.let { Title(it, TextStyleUi.Title) }
                Children(node.children, onIntent, webs)
            }
        }

        is UiNodeUi.Section -> HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            Title(node.title, TextStyleUi.Heading)
            node.description?.let { Note(it) }
            Children(node.children, onIntent, webs)
        }

        is UiNodeUi.Divider -> HbDivider(modifier)

        is UiNodeUi.Spacer -> Spacer(modifier.size(node.size.spacing()))
    }
}

@Composable
private fun ContentView(
    node: UiNodeUi.Content,
    onIntent: (SurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    webs: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    when (node) {
        is UiNodeUi.Text -> if (node.style.isHeading) {
            Title(node.text, node.style, modifier)
        } else {
            HbText(node.text, modifier, style = node.style.style(node.tone), color = node.style.color())
        }

        is UiNodeUi.Markdown -> MarkdownView(node.text, onIntent, modifier)

        is UiNodeUi.Badge -> HbBadge(node.text, modifier, node.tone.hb())

        is UiNodeUi.Progress -> ProgressView(node, modifier)

        is UiNodeUi.KeyValue -> KeyValueView(node, modifier)

        is UiNodeUi.Empty -> HbEmptyState(node.title, modifier, node.description)

        is UiNodeUi.Diagram -> HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
            node.title?.let { Title(it, TextStyleUi.Title) }
            if (node.source.isNotBlank()) MarkdownView(plantUmlFence(node.source), onIntent)
        }

        is UiNodeUi.Table -> TableView(node, onIntent, modifier)

        is UiNodeUi.More -> Note(stringResource(Res.string.service_ui_more, node.count), modifier)

        is UiNodeUi.Web -> WebView(webs[node.key], modifier)
    }
}

/**
 * A web node's embedded browser document at a fixed height (native views cannot size to their content), or a note
 * while web documents are off or cannot be shown here.
 */
@Composable
private fun WebView(document: ComposableComponent?, modifier: Modifier = Modifier) {
    if (document == null) {
        Note(stringResource(Res.string.service_ui_web_unavailable), modifier)
    } else {
        document.Content(modifier.fillMaxWidth().height(HbTheme.dimensions.webDocumentHeight))
    }
}

/** Markdown laid out with the surface; links go to the store, which asks before opening them. */
@Composable
private fun MarkdownView(text: String, onIntent: (SurfaceIntent) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseHbMarkdown(text) }
    SelectionContainer(modifier.fillMaxWidth()) {
        HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            blocks.forEach { block ->
                key(block.id) {
                    HbMarkdownBlockContent(block, onLinkClick = { onIntent(SurfaceIntent.OpenLink(it)) })
                }
            }
        }
    }
}

@Composable
private fun ProgressView(node: UiNodeUi.Progress, modifier: Modifier = Modifier) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        node.label?.let { Note(it) }
        val value = node.value
        if (value == null) {
            HbActivityIndicator(contentDescription = node.label)
        } else {
            HbProgressBar(value, contentDescription = node.label)
        }
    }
}

@Composable
private fun KeyValueView(node: UiNodeUi.KeyValue, modifier: Modifier = Modifier) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        node.items.forEach { pair ->
            HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.m, verticalAlignment = Alignment.Top) {
                HbText(
                    pair.key,
                    Modifier.weight(1f),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
                HbText(pair.value, Modifier.weight(2f))
            }
        }
    }
}

@Composable
private fun TableView(node: UiNodeUi.Table, onIntent: (SurfaceIntent) -> Unit, modifier: Modifier = Modifier) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxs) {
        HbRow(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.s), gap = HbTheme.spacing.m) {
            node.columns.forEach { column ->
                HbText(
                    column.title,
                    Modifier.weight(column.weight),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        HbDivider()
        if (node.rows.isEmpty()) node.emptyText?.let { Note(it, Modifier.padding(HbTheme.spacing.s)) }
        node.rows.forEach { row ->
            key(row.key) {
                HbPressableRow(
                    onClick = row.press?.let { press -> { onIntent(SurfaceIntent.Press(press)) } },
                    enabled = !row.isBusy,
                ) {
                    row.cells.forEachIndexed { index, cell ->
                        HbText(cell, Modifier.weight(node.columns.getOrNull(index)?.weight ?: 1f), maxLines = 1)
                    }
                }
            }
        }
        if (node.hidden > 0) {
            Note(
                stringResource(Res.string.service_ui_more, node.hidden),
                Modifier.padding(horizontal = HbTheme.spacing.s),
            )
        }
    }
}

/** Text that titles what follows it: semibold and a heading for screen readers. */
@Composable
private fun Title(text: String, style: TextStyleUi, modifier: Modifier = Modifier) {
    HbText(text, modifier.semantics { heading() }, style = style.style(), color = style.color())
}

/** Secondary caption text. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    HbText(text, modifier, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
}

@Composable
private fun Children(
    children: ImmutableList<UiNodeUi>,
    onIntent: (SurfaceIntent) -> Unit,
    webs: ImmutableMap<String, ComposableComponent>,
) {
    children.forEach { child -> key(child.key) { UiNodeView(child, onIntent, webs = webs) } }
}

/**
 * Children of a row. Buttons, badges and spacers keep their own width; other nodes share the rest, and text and nested
 * rows take no more than they need, so a field or a long text never pushes a button out of the row.
 */
@Composable
private fun RowScope.RowChildren(
    children: ImmutableList<UiNodeUi>,
    onIntent: (SurfaceIntent) -> Unit,
    webs: ImmutableMap<String, ComposableComponent>,
) {
    children.forEach { child ->
        key(child.key) {
            val share = when {
                child.keepsWidth() -> Modifier
                child is UiNodeUi.Text || child is UiNodeUi.Row -> Modifier.weight(1f, fill = false)
                else -> Modifier.weight(1f)
            }
            UiNodeView(child, onIntent, share, webs)
        }
    }
}

private fun UiNodeUi.keepsWidth(): Boolean =
    this is UiNodeUi.Button || this is UiNodeUi.Badge || this is UiNodeUi.Spacer

/** A PlantUML fence around [source], longer than any backtick run in it, so a bound source cannot close it early. */
private fun plantUmlFence(source: String): String {
    val longest = Backticks.findAll(source).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(FENCE_LENGTH, longest + 1))
    return "${fence}plantuml\n$source\n$fence"
}

private val Backticks = Regex("`+")
private const val FENCE_LENGTH = 3
