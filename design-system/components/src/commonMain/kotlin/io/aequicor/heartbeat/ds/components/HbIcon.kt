package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Font-independent icon with a shared size and theme tint across Material, Fluent and macOS.
 * Pass null for [contentDescription] when the parent control or adjacent text already labels it.
 * Use a size modifier for compact metadata; the parent action retains its complete touch target.
 */
@Composable
public fun HbIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = HbTheme.colors.textSecondary,
) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(HbTheme.dimensions.iconSize),
        colorFilter = ColorFilter.tint(tint),
    )
}
