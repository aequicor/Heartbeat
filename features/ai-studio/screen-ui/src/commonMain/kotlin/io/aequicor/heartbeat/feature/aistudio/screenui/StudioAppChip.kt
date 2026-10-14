package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.miniapp_open
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioAppUi
import org.jetbrains.compose.resources.stringResource

/** A native launcher in the composer's wrapping control row; it never mounts a second app body. */
@Composable
internal fun StudioAppChip(
    app: StudioAppUi,
    paneId: Int,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbTooltip(app.title, modifier) {
        HbChip(
            label = app.title,
            modifier = Modifier.widthIn(max = HbTheme.dimensions.composerLabelMaxWidth).testTag("app-chip-$paneId"),
            icon = HbIcons.Layers,
            onClick = { onIntent(AiStudioScreenIntent.OpenApp(paneId, app.surfaceId)) },
            trailingIcon = HbIcons.ExternalLink,
            accessibleLabel = stringResource(Res.string.miniapp_open, app.title),
        )
    }
}
