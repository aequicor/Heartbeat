package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbChatComposer
import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatMessageBubble
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerLayout
import io.aequicor.heartbeat.ds.components.HbComposerMenuButton
import io.aequicor.heartbeat.ds.components.HbComposerMenuStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMessageAppearance
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbStudioMark
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import kotlinx.collections.immutable.persistentListOf

/** Flat navigation, continuous agent prose and the compact composer in the current host density. */
@Composable
internal fun StudioExample(modifier: Modifier = Modifier) {
    HbColumn(modifier.fillMaxWidth().background(HbTheme.surfaces.backdrop), gap = HbTheme.spacing.none) {
        StudioNavigationExample()
        HbDivider()
        HbColumn(Modifier.padding(HbTheme.spacing.xl), gap = HbTheme.spacing.xl) {
            HbChatMessageBubble(
                message = HbChatMessage(
                    id = "studio-catalog-agent",
                    author = hbString(HbString.Agent),
                    text = hbString(HbString.ToolDemoHint),
                    appearance = HbMessageAppearance(isUnified = true, widthFraction = 1f),
                ),
            )
            StudioComposerExample()
        }
    }
}

@Composable
private fun StudioNavigationExample() {
    HbColumn(
        Modifier.fillMaxWidth().background(HbTheme.surfaces.sidebar).padding(HbTheme.spacing.m),
        gap = HbTheme.spacing.xs,
    ) {
        HbWindowDragArea(Modifier.fillMaxWidth()) {
            HbRow(Modifier.padding(HbTheme.spacing.xs), gap = HbTheme.spacing.m) {
                HbStudioMark(Modifier.size(HbTheme.dimensions.headerAvatarSize))
                HbText(hbString(HbString.Workspace), style = HbTheme.typography.title)
            }
        }
        HbNavigationItem(
            label = hbString(HbString.ChatTitle),
            onClick = {},
            isSelected = true,
            selectedBackground = HbTheme.surfaces.selected,
            selectedForeground = HbTheme.surfaces.onSelected,
            minHeight = HbTheme.dimensions.navigationRowHeight,
        )
        HbNavigationItem(
            label = hbString(HbString.SeedPrompt),
            onClick = {},
            minHeight = HbTheme.dimensions.navigationRowHeight,
        )
    }
}

@Composable
private fun StudioComposerExample() {
    HbChatComposer(
        value = "",
        onValueChange = {},
        onSend = {},
        onStop = {},
        sendLabel = hbString(HbString.Send),
        stopLabel = hbString(HbString.Stop),
        layout = HbComposerLayout.Panel,
        inputMaxHeight = HbTheme.dimensions.editorMaxHeight,
        placeholder = hbString(HbString.ComposerPlaceholder),
        leadingContent = {
            HbComposerMenuButton(
                label = hbString(HbString.AddContext),
                actions = persistentListOf(HbComposerAction("note", hbString(HbString.InsertNote))),
                isExpanded = false,
                onExpandedChange = {},
                onAction = {},
                icon = HbIcons.Plus,
                style = HbComposerMenuStyle.Circle,
            )
        },
        trailingContent = {
            HbComposerMenuButton(
                label = hbString(HbString.DemoModel),
                actions = persistentListOf(HbComposerAction("demo", hbString(HbString.DemoModel))),
                isExpanded = false,
                onExpandedChange = {},
                onAction = {},
                icon = HbIcons.Layers,
                style = HbComposerMenuStyle.Pill,
            )
        },
    )
}

@Preview
@Composable
private fun DesktopLightStudioPreview() {
    HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) { StudioExample() }
}

@Preview
@Composable
private fun DesktopDarkStudioPreview() {
    HbTheme(darkTheme = true, dimensions = HbDimensions.Desktop) { StudioExample() }
}

@Preview
@Composable
private fun MobileStudioPreview() {
    HbTheme(dimensions = HbDimensions.Mobile) { StudioExample() }
}
