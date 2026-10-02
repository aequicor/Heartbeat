package io.aequicor.heartbeat.platform.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbImage
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.hbSurface
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.platform.shared.resources.Res
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_accessibility
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_close
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_instruction
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_screen_recording
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_tile
import io.aequicor.heartbeat.platform.shared.resources.computer_use_guide_title
import org.jetbrains.compose.resources.stringResource

/**
 * Panel guiding a macOS permission grant: the user drags the application tile into the list on the open System
 * Settings page. The desktop host owns the window, the drag gesture ([tileModifier]) and the application shown on
 * the tile, which is the one macOS attributes the permission to; this only draws them. Without an encoded
 * [appIcon] the tile shows a generic icon. The host supplies [HbTheme] so the panel follows its theme.
 */
@Composable
fun ComputerUsePermissionGuidePanel(
    permission: ComputerUsePermission,
    appName: String,
    appIcon: ByteArray?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    tileModifier: Modifier = Modifier,
) {
    HbCard(
        modifier.width(HbTheme.dimensions.computerUsePermissionGuideWidth).testTag("computer-use-guide"),
        contentPadding = HbTheme.spacing.l,
    ) {
        HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
            HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
                HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.xxs) {
                    HbText(
                        stringResource(permission.title()),
                        Modifier.semantics { heading() },
                        style = HbTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    )
                    HbText(
                        stringResource(Res.string.computer_use_guide_instruction, appName),
                        style = HbTheme.typography.caption,
                        color = HbTheme.colors.textSecondary,
                    )
                }
                HbIconButton(
                    HbIcons.Close,
                    stringResource(Res.string.computer_use_guide_close),
                    onClose,
                    Modifier.testTag("computer-use-guide-close"),
                    size = HbTheme.dimensions.compactControlHeight,
                )
            }
            ApplicationTile(appName, appIcon, tileModifier)
        }
    }
}

/** Looks like the application's row in Finder; the whole tile, including its surface, is what the user drags. */
@Composable
private fun ApplicationTile(appName: String, appIcon: ByteArray?, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.computer_use_guide_tile, appName)
    val iconModifier = Modifier.size(HbTheme.dimensions.computerUsePermissionGuideIconSize)
    HbRow(
        modifier.fillMaxWidth()
            .hbSurface(HbTheme.colors.surfaceElevated, HbTheme.shapes.small)
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.s)
            .testTag("computer-use-guide-tile")
            .clearAndSetSemantics { contentDescription = description },
        gap = HbTheme.spacing.m,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (appIcon == null) {
            HbIcon(HbIcons.Laptop, null, iconModifier)
        } else {
            HbImage(appIcon, appName, iconModifier)
        }
        HbText(appName, Modifier.weight(1f), style = HbTheme.typography.label, maxLines = 1)
    }
}

private fun ComputerUsePermission.title() = when (this) {
    ComputerUsePermission.ScreenRecording -> Res.string.computer_use_guide_screen_recording
    ComputerUsePermission.Accessibility -> Res.string.computer_use_guide_accessibility
}

/** Localized title shared by the Compose window and its native macOS panel. */
@Composable
fun computerUsePermissionGuideTitle(): String = stringResource(Res.string.computer_use_guide_title)

@Preview
@Composable
private fun ComputerUsePermissionGuidePanelLightPreview() {
    HbTheme(darkTheme = false) {
        ComputerUsePermissionGuidePanel(ComputerUsePermission.ScreenRecording, "Heartbeat", null, {})
    }
}

@Preview
@Composable
private fun ComputerUsePermissionGuidePanelDarkPreview() {
    HbTheme(darkTheme = true) {
        ComputerUsePermissionGuidePanel(ComputerUsePermission.Accessibility, "IntelliJ IDEA", null, {})
    }
}
