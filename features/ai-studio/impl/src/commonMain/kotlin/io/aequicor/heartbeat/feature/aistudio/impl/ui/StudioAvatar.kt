package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme

/** A local monogram identifies a conversation without inventing profile photos or user identities. */
@Composable
internal fun StudioAvatar(title: String, modifier: Modifier = Modifier, isSmall: Boolean = false) {
    val initials = title.trim().split(' ').mapNotNull { word ->
        word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
    }.take(2).joinToString("")
    val colors = HbTheme.colors
    val accent = if (title.hashCode() % 2 == 0) colors.brand else colors.secondary
    val tint = accent.copy(alpha = 0.32f).compositeOver(colors.surface)
    Box(
        modifier.size(
            if (isSmall) HbTheme.dimensions.headerAvatarSize else HbTheme.dimensions.avatarSize,
        ).background(
            Brush.linearGradient(listOf(tint, HbTheme.surfaces.avatar)),
            CircleShape,
        ).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        HbText(initials, style = HbTheme.typography.label, color = HbTheme.colors.textPrimary, maxLines = 1)
    }
}
