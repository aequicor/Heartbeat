package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList

private val log = Log.tag("DS/ComposerMenu")

/** The standard toolbar action, a circular add control, or a pill with an optional accent fill. */
public enum class HbComposerMenuStyle { Standard, Circle, Pill, AccentPill }

/**
 * Controlled toolbar menu. The owner supplies localized labels and handles action identifiers.
 * Arrow keys, Home/End and Tab navigate enabled commands; Enter/Space activate them.
 * Escape and outside clicks dismiss the menu and restore focus to its trigger.
 * [icon] uses the shared vector style with the separate [accessibleLabel] announced.
 * [isIcon] retains support for custom text glyphs; prefer [icon] for design-system actions.
 */
@Composable
public fun HbComposerMenuButton(
    label: String,
    actions: ImmutableList<HbComposerAction>,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    headerLabel: String? = null,
    accessibleLabel: String = label,
    isIcon: Boolean = false,
    icon: ImageVector? = null,
    style: HbComposerMenuStyle = HbComposerMenuStyle.Standard,
) {
    require(actions.map { it.id }.distinct().size == actions.size) { "Composer action ids must be unique" }
    val triggerFocus = remember { FocusRequester() }
    var hasOpened by remember { mutableStateOf(false) }
    val isOpen = isExpanded && enabled && actions.isNotEmpty()
    SideEffect(isOpen) {
        if (!isOpen && hasOpened) triggerFocus.requestFocus()
        hasOpened = isOpen
    }
    val changeExpanded: (Boolean) -> Unit = {
        log.i { "composer menu expanded=$it" }
        onExpandedChange(it)
    }
    Box(modifier = modifier) {
        HbTooltip(accessibleLabel) {
            ComposerMenuTrigger(
                label = label,
                accessibleLabel = accessibleLabel,
                onClick = { changeExpanded(!isOpen) },
                onOpen = { changeExpanded(true) },
                modifier = Modifier.focusRequester(triggerFocus),
                enabled = enabled && actions.isNotEmpty(),
                isIcon = isIcon,
                icon = icon,
                style = style,
            )
        }
        if (isOpen) {
            ComposerMenuPopup(
                actions = actions,
                label = headerLabel ?: accessibleLabel,
                onDismiss = { changeExpanded(false) },
                onAction = { action ->
                    log.i { "composer menu action activated" }
                    changeExpanded(false)
                    onAction(action)
                },
                headerLabel = headerLabel,
            )
        }
    }
}

@Composable
private fun ComposerMenuTrigger(
    label: String,
    accessibleLabel: String,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isIcon: Boolean = false,
    icon: ImageVector? = null,
    style: HbComposerMenuStyle = HbComposerMenuStyle.Standard,
) {
    val triggerModifier = modifier
        .semantics { contentDescription = accessibleLabel }
        .onPreviewKeyEvent { event ->
            val isOpenKey = event.key == Key.DirectionDown || event.key == Key.DirectionUp
            if (enabled && isOpenKey && event.type == KeyEventType.KeyDown) {
                onOpen()
                true
            } else {
                false
            }
        }
    if (style == HbComposerMenuStyle.Pill || style == HbComposerMenuStyle.AccentPill) {
        ComposerMenuPill(label, onClick, triggerModifier, icon, enabled, style == HbComposerMenuStyle.AccentPill)
    } else if (style == HbComposerMenuStyle.Circle) {
        ComposerIconButton(
            icon,
            accessibleLabel,
            onClick,
            triggerModifier,
            enabled = enabled,
            fallbackSymbol = label,
            shape = HbTheme.shapes.small,
            size = HbTheme.studioDimensions.composerActionSize,
            background = HbTheme.studioColors.composerPill,
            iconSize = HbTheme.dimensions.iconSize,
            tooltipText = null,
        )
    } else if (icon != null || isIcon) {
        ComposerIconButton(
            icon,
            accessibleLabel,
            onClick,
            triggerModifier,
            enabled = enabled,
            fallbackSymbol = label,
            tooltipText = null,
        )
    } else {
        HbButton(label, onClick, triggerModifier, style = HbButtonStyle.Quiet, enabled = enabled)
    }
}
