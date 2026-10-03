package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.adaptive.AdaptiveButton
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle
import io.aequicor.heartbeat.ds.tokens.HbColors

private val log = Log.tag("DS/Controls")

/**
 * Emphasis of an accessible action, independent of its selected visual implementation.
 * [Primary] is the one main action of a region; [Secondary] a quiet filled action; [Ghost] has no fill until hover;
 * [Danger] confirms a destructive action and is used only where the consequence is spelled out next to it.
 */
public enum class HbButtonStyle { Primary, Secondary, Ghost, Danger }

/** Height of a button: [Regular] 32dp and [Small] 28dp on desktop; touch hosts never go below their touch target. */
public enum class HbButtonSize { Regular, Small }

/**
 * Flat or platform-native action with content-free interaction logging. Hover and press are quiet fills without
 * outlines; the focus ring appears only for keyboard focus.
 */
@Composable
public fun HbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HbButtonStyle = HbButtonStyle.Primary,
    enabled: Boolean = true,
    size: HbButtonSize = HbButtonSize.Regular,
) {
    val loggedClick = {
        log.i { "button pressed style=$style" }
        onClick()
    }
    if (HbTheme.visualStyle == HbVisualStyle.Platform) {
        AdaptiveButton(
            text = text,
            onClick = loggedClick,
            modifier = modifier.captionClientArea().heightIn(min = buttonHeight(size)),
            enabled = enabled,
            primary = style == HbButtonStyle.Primary,
            dimensions = HbTheme.dimensions,
        )
    } else {
        FlatButton(text, loggedClick, modifier.captionClientArea(), style, enabled, size)
    }
}

@Composable
@ReadOnlyComposable
private fun buttonHeight(size: HbButtonSize): Dp = controlTargetSize(
    when (size) {
        HbButtonSize.Regular -> HbTheme.dimensions.controlHeight
        HbButtonSize.Small -> HbTheme.dimensions.compactControlHeight
    },
)

@Composable
private fun FlatButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HbButtonStyle = HbButtonStyle.Primary,
    enabled: Boolean = true,
    size: HbButtonSize = HbButtonSize.Regular,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val colors = HbTheme.colors
    val shape = RoundedCornerShape(HbTheme.dimensions.controlCornerRadius)
    val base = buttonFill(colors, style, enabled)
    val targetBackground = when {
        !enabled -> base
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val motion = HbTheme.motion
    // Semantic roles change fill and label together; only hover and press changes interpolate.
    val background = key(colors, style, enabled) {
        animateColorAsState(
            targetValue = targetBackground,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "buttonSurface",
        ).value
    }
    val foreground = buttonContent(colors, style, enabled)
    Box(
        modifier = modifier
            .heightIn(min = buttonHeight(size))
            .widthIn(min = buttonHeight(size))
            .hbFocusOutline(isFocused, shape)
            .clickable(interactionSource, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .hbControlSurface(background, shape)
            .padding(horizontal = HbTheme.spacing.l, vertical = HbTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        HbText(
            text = text,
            style = HbTheme.typography.label.copy(fontWeight = FontWeight.Medium),
            color = foreground,
            maxLines = 1,
        )
    }
}

/**
 * Controlled input. The caller owns its text; logs never contain the input value.
 * [isSecret] makes a secure single-line field: obfuscated text marked as a password for accessibility,
 * no cut/copy, a password keyboard without autocorrect, and nothing written to saved state.
 * A secret field is always single-line, whatever [singleLine] says.
 */
@Composable
public fun HbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    accessibleLabel: String = placeholder,
    isSecret: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val loggedChange: (String) -> Unit = {
        log.d { "text input changed length=${it.length}" }
        onValueChange(it)
    }
    // The legacy native kit editors hide their scroll state. All styles share this observable editor.
    FlatTextField(
        value,
        loggedChange,
        modifier.semantics {
            if (accessibleLabel.isNotBlank()) contentDescription = accessibleLabel
            if (isSecret) password()
        },
        placeholder,
        enabled,
        singleLine || isSecret,
        isSecret,
        leadingContent,
        trailingContent,
    )
}

@Composable
private fun FlatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isSecret: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = HbTheme.colors
    val isHovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(HbTheme.dimensions.fieldCornerRadius)
    val fill = if (isHovered && enabled) {
        colors.interactionHoverOverlay.compositeOver(colors.inputFill)
    } else {
        colors.inputFill
    }
    HbEditableText(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = controlTargetSize(HbTheme.dimensions.controlHeight))
            .hbFocusOutline(isFocused, shape, isTextInput = true)
            .background(fill, shape)
            .hoverable(interactionSource, enabled),
        enabled = enabled,
        singleLine = singleLine,
        interactionSource = interactionSource,
        placeholder = placeholder,
        contentPadding = PaddingValues(
            horizontal = HbTheme.spacing.m,
            vertical = if (leadingContent == null && trailingContent == null) {
                HbTheme.spacing.xs
            } else {
                HbTheme.spacing.none
            },
        ),
        isSecret = isSecret,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}

/** Resting fill of a flat button; semantic roles keep their fill only while enabled. */
private fun buttonFill(colors: HbColors, style: HbButtonStyle, enabled: Boolean): Color = when {
    style == HbButtonStyle.Primary && enabled -> colors.primary
    style == HbButtonStyle.Danger && enabled -> colors.error
    style == HbButtonStyle.Ghost -> Color.Transparent
    else -> colors.buttonFill
}

/** Label colour paired with [buttonFill]. */
private fun buttonContent(colors: HbColors, style: HbButtonStyle, enabled: Boolean): Color = when {
    !enabled -> colors.textSecondary
    style == HbButtonStyle.Primary -> colors.onPrimary
    style == HbButtonStyle.Danger -> colors.onError
    else -> colors.textPrimary
}
