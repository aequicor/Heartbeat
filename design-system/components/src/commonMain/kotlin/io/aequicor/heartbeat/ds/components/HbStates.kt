package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Empty or not-yet-available content: a centered [title], optional [description] and one [action].
 * No illustration or card; it reads as quiet text in the content column.
 */
@Composable
public fun HbEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    HbColumn(
        modifier.fillMaxWidth().padding(HbTheme.spacing.xxl),
        gap = HbTheme.spacing.s,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HbText(
            title,
            Modifier.widthIn(max = HbTheme.dimensions.dialogMaxWidth),
            style = HbTheme.typography.label.copy(fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        )
        if (description != null) {
            HbText(
                description,
                Modifier.widthIn(max = HbTheme.dimensions.dialogMaxWidth),
                style = HbTheme.typography.caption.copy(textAlign = TextAlign.Center),
                color = HbTheme.colors.textSecondary,
            )
        }
        action?.invoke()
    }
}

/**
 * Inline status message such as a failed load or save: a [message] on a quiet [tone] fill with optional recovery
 * [actions] (Retry, Dismiss). Announced politely to screen readers. Meaning is carried by the text, not the color.
 */
@Composable
public fun HbBanner(
    message: String,
    modifier: Modifier = Modifier,
    tone: HbTone = HbTone.Danger,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = tonePalette(tone)
    HbRow(
        modifier.fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(palette.background, HbTheme.shapes.small)
            .padding(horizontal = HbTheme.spacing.l, vertical = HbTheme.spacing.s),
        gap = HbTheme.spacing.m,
    ) {
        HbText(message, Modifier.weight(1f), style = HbTheme.typography.label, color = palette.foreground)
        actions()
    }
}

/** Loading placeholder of a region: a small activity indicator with a secondary [label]. */
@Composable
public fun HbLoadingState(label: String, modifier: Modifier = Modifier) {
    HbRow(
        modifier.padding(HbTheme.spacing.m).semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        gap = HbTheme.spacing.s,
    ) {
        HbActivityIndicator()
        HbText(label, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
    }
}

@Preview
@Composable
private fun StatesLightPreview() {
    HbTheme(darkTheme = false) { StatesPreviewContent() }
}

@Preview
@Composable
private fun StatesDarkPreview() {
    HbTheme(darkTheme = true) { StatesPreviewContent() }
}

@Composable
private fun StatesPreviewContent() {
    HbColumn {
        HbBanner("Не удалось сохранить изменение. Предыдущее значение сохранено.") {
            HbButton("Повторить", {}, style = HbButtonStyle.Secondary, size = HbButtonSize.Small)
        }
        HbLoadingState("Загрузка…")
        HbEmptyState("Ничего не найдено", description = "Измените запрос поиска")
    }
}
