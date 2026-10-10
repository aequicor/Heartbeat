package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/Settings")

/**
 * Group of [HbSettingsRow]s under a [title] (semibold 13–14sp) with an optional [description] (12sp secondary).
 * Rows are separated by hairlines; the group has no card, border or background of its own.
 * [trailingContent] hosts group actions such as "Reset all".
 */
@Composable
public fun HbSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailingContent: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbRow(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.s) {
            HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.xxs) {
                HbText(
                    title,
                    Modifier.semantics { heading() },
                    style = HbTheme.typography.title.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                if (description != null) {
                    HbText(description, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
                }
            }
            trailingContent()
        }
        HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.none, content = content)
    }
}

/**
 * One setting: a [title], an optional [description] in 12sp secondary text and the control in [trailingContent]
 * (switch, button, value). Dense on desktop ([io.aequicor.heartbeat.ds.tokens.HbDimensions.settingsRowHeight]),
 * touch-sized on mobile. With [onClick] the whole row is a button with quiet hover and pressed fills and a
 * keyboard-only focus ring; without it only the trailing control is interactive. [role] names the click for
 * accessibility: one of several exclusive options is a [Role.RadioButton] with [isSelected], inside a section marked
 * `selectableGroup()`. The title never ends with an ellipsis: it fades and shows its full text in a tooltip.
 */
@Composable
public fun HbSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    role: Role = Role.Button,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val colors = HbTheme.colors
    val shape = RoundedCornerShape(HbTheme.dimensions.controlCornerRadius)
    val background = settingsRowBackground(interactions, isInteractive = onClick != null && enabled, isSelected)
    val action = if (onClick == null) {
        Modifier
    } else {
        Modifier.hbFocusOutline(isFocused, shape).clickable(interactions, null, enabled, role = role) {
            log.i { "settings row pressed" }
            onClick()
        }
    }
    HbRow(
        modifier.fillMaxWidth()
            .heightIn(min = controlTargetSize(HbTheme.dimensions.settingsRowHeight))
            .then(action)
            .background(background, shape)
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.s),
        gap = HbTheme.spacing.l,
    ) {
        leadingContent?.invoke()
        HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.xxs) {
            HbText(
                title,
                style = HbTheme.typography.label,
                color = if (enabled) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
            )
            if (description != null) {
                HbText(description, style = HbTheme.typography.caption, color = colors.textSecondary)
            }
        }
        trailingContent()
    }
}

@Composable
private fun settingsRowBackground(
    interactions: MutableInteractionSource,
    isInteractive: Boolean,
    isSelected: Boolean,
): Color {
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val colors = HbTheme.colors
    val base = if (isSelected) colors.selectedContainer else Color.Transparent
    val target = when {
        !isInteractive -> base
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val motion = HbTheme.motion
    return key(colors, isSelected) {
        animateColorAsState(
            target,
            if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "row",
        ).value
    }
}

@Preview
@Composable
private fun SettingsLightPreview() {
    HbTheme(darkTheme = false) { SettingsPreviewContent() }
}

@Preview
@Composable
private fun SettingsDarkPreview() {
    HbTheme(darkTheme = true) { SettingsPreviewContent() }
}

@Composable
private fun SettingsPreviewContent() {
    HbSettingsSection("Поиск", description = "Провайдеры поиска профиля") {
        HbSettingsRow("Предпочитать инструменты движка", description = "Нативный поиск движка, если он есть") {
            HbSwitch(true, {}, "Предпочитать инструменты движка")
        }
        HbDivider()
        HbSettingsRow("Querit", description = "Ключ сохранён", onClick = {})
    }
}
