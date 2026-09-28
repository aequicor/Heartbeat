package io.aequicor.heartbeat.feature.togglespanel.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.Res
import io.aequicor.heartbeat.feature.togglespanel.impl.resources.flags_back
import org.jetbrains.compose.resources.stringResource

/**
 * Header of the panel opened outside the settings host (legacy entry with unified settings disabled): the window
 * drag strip with the traffic-light inset, "back" and the title, in the height of the studio header.
 */
@Composable
internal fun StandaloneHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    HbWindowDragArea(
        modifier.fillMaxWidth().height(HbTheme.dimensions.headerHeight).background(HbTheme.surfaces.header),
    ) {
        HbRow(
            Modifier.fillMaxWidth().padding(start = HbTheme.dimensions.titlebarLeadingInset, end = HbTheme.spacing.m),
            gap = HbTheme.spacing.s,
        ) {
            HbIconButton(
                HbIcons.ArrowLeft,
                stringResource(Res.string.flags_back),
                onBack,
                Modifier.testTag("flags-back"),
            )
            HbText(title, style = HbTheme.typography.title.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}
