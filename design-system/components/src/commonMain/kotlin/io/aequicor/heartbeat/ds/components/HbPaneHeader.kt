package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Header of a window pane in the studio geometry: [io.aequicor.heartbeat.ds.tokens.HbDimensions.headerHeight],
 * a semibold 13–14sp [title], optional [navigation] before it (for example "back") and trailing [actions].
 * The strip moves the desktop window through [HbWindowDragArea], which reserves native caption controls.
 * [leadingInset] is additional content padding within that safe region.
 * Hosts own this header — features inside a host draw
 * only their content.
 */
@Composable
public fun HbPaneHeader(
    title: String,
    modifier: Modifier = Modifier,
    leadingInset: Dp = HbTheme.spacing.m,
    background: Color = HbTheme.surfaces.header,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    HbWindowDragArea(modifier.fillMaxWidth().height(HbTheme.dimensions.headerHeight).background(background)) {
        HbRow(
            Modifier.fillMaxWidth().padding(start = leadingInset, end = HbTheme.spacing.m),
            gap = HbTheme.spacing.s,
        ) {
            navigation?.invoke()
            HbText(
                title,
                Modifier.weight(1f).semantics { heading() },
                style = HbTheme.typography.title.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            actions()
        }
    }
}

@Preview
@Composable
private fun PaneHeaderLightPreview() {
    HbTheme(darkTheme = false) {
        HbPaneHeader("Модели и движки", navigation = { HbIconButton(HbIcons.ArrowLeft, "Назад", {}) })
    }
}

@Preview
@Composable
private fun PaneHeaderDarkPreview() {
    HbTheme(darkTheme = true) { HbPaneHeader("Поиск") }
}
