package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Flat search input with a magnifier, an accessible clear action, and an optional caller-owned
 * shortcut hint. It does not register shortcuts or change search behavior.
 */
@Composable
fun HbSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    shortcutLabel: String = "",
    enabled: Boolean = true,
) {
    HbTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        leadingContent = {
            HbIcon(
                HbIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(HbTheme.dimensions.iconSmallSize),
                tint = HbTheme.colors.textSecondary,
            )
        },
        trailingContent = {
            if (value.isNotEmpty()) {
                HbIconButton(HbIcons.Close, clearLabel, { onValueChange("") }, enabled = enabled)
            } else if (shortcutLabel.isNotEmpty()) {
                HbText(shortcutLabel, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
            }
        },
    )
}

@Preview
@Composable
private fun SearchFieldLightPreview() {
    HbTheme(darkTheme = false) { HbSearchField("", {}, "Search conversations", "Clear search", shortcutLabel = "⌘K") }
}

@Preview
@Composable
private fun SearchFieldDarkPreview() {
    HbTheme(darkTheme = true) { HbSearchField("Heartbeat", {}, "Search conversations", "Clear search") }
}
