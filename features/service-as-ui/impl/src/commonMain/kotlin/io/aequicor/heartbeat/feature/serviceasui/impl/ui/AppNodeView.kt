package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * A bounded app layout gives its single web node the remaining space, without an enclosing scroll container.
 * Start fills that space; a modal measures its content with the same upper bound and may shrink after a tab change.
 */
@Composable
internal fun AppNodeView(
    node: UiNodeUi,
    onIntent: (SurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    webs: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
    isContentFill: Boolean = true,
) {
    if (!node.containsWeb()) {
        UiNodeView(node, onIntent, modifier, webs)
        return
    }
    when (node) {
        is UiNodeUi.Web -> (webs[node.key] ?: OpeningDocument).Content(modifier.appSize(isContentFill))

        is UiNodeUi.Column -> HbColumn(modifier.appSize(isContentFill), gap = node.gap.spacing()) {
            AppChildren(node.children, onIntent, webs, isContentFill)
        }

        is UiNodeUi.Row -> AppRow(node, onIntent, modifier, webs, isContentFill)

        is UiNodeUi.Card -> HbCard(modifier.appSize(isContentFill), contentPadding = HbTheme.spacing.l) {
            node.title?.let { HbText(it, style = HbTheme.typography.title) }
            AppChildren(node.children, onIntent, webs, isContentFill)
        }

        is UiNodeUi.Section -> HbColumn(modifier.appSize(isContentFill), gap = HbTheme.spacing.s) {
            HbText(node.title, style = HbTheme.typography.title)
            node.description?.let { HbText(it, color = HbTheme.colors.textSecondary) }
            AppChildren(node.children, onIntent, webs, isContentFill)
        }

        is UiNodeUi.Content, is UiNodeUi.Input, is UiNodeUi.Divider, is UiNodeUi.Spacer ->
            UiNodeView(node, onIntent, modifier.fillMaxWidth(), webs)
    }
}

@Composable
private fun AppRow(
    node: UiNodeUi.Row,
    onIntent: (SurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    webs: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
    isContentFill: Boolean = true,
) {
    BoxWithConstraints(modifier.appSize(isContentFill)) {
        if (node.isWrapped && maxWidth < HbTheme.dimensions.compactBreakpoint) {
            HbColumn(Modifier.appSize(isContentFill), gap = node.gap.spacing()) {
                AppChildren(node.children, onIntent, webs, isContentFill)
            }
        } else {
            HbRow(Modifier.appSize(isContentFill), gap = node.gap.spacing()) {
                node.children.forEach { child ->
                    key(child.key) {
                        val size = when {
                            child is UiNodeUi.Button || child is UiNodeUi.Badge || child is UiNodeUi.Spacer -> Modifier
                            child is UiNodeUi.Text || child is UiNodeUi.Row -> Modifier.weight(1f, fill = false)
                            else -> Modifier.weight(1f)
                        }
                        AppNodeView(child, onIntent, size, webs, isContentFill)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AppChildren(
    children: ImmutableList<UiNodeUi>,
    onIntent: (SurfaceIntent) -> Unit,
    webs: ImmutableMap<String, ComposableComponent>,
    isContentFill: Boolean,
) {
    children.forEach { child ->
        key(child.key) {
            AppNodeView(
                child,
                onIntent,
                if (child.containsWeb()) Modifier.weight(1f, fill = isContentFill) else Modifier,
                webs,
                isContentFill,
            )
        }
    }
}

private fun Modifier.appSize(isFill: Boolean): Modifier = if (isFill) fillMaxSize() else fillMaxWidth()

/** Whether the subtree contains the spec's isolated document. The validator allows at most one body web node. */
internal fun UiNodeUi.containsWeb(): Boolean = when (this) {
    is UiNodeUi.Web -> true
    is UiNodeUi.Column -> children.any { it.containsWeb() }
    is UiNodeUi.Row -> children.any { it.containsWeb() }
    is UiNodeUi.Card -> children.any { it.containsWeb() }
    is UiNodeUi.Section -> children.any { it.containsWeb() }
    is UiNodeUi.Content, is UiNodeUi.Input, is UiNodeUi.Divider, is UiNodeUi.Spacer -> false
}
