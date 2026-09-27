package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbGlassScene
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_back
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_description
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_empty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AiStudioScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    HbGlassScene(modifier.fillMaxSize().testTag("ai-studio")) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().hbVerticalScroll(rememberScrollState())
                .padding(HbTheme.spacing.xxl),
            contentAlignment = Alignment.Center,
        ) {
            HbPanel(Modifier.widthIn(max = HbTheme.dimensions.chatMessageMaxWidth)) {
                HbColumn(Modifier.padding(HbTheme.spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
                    HbText(stringResource(Res.string.studio_title), style = HbTheme.typography.display)
                    HbText(stringResource(Res.string.studio_empty), style = HbTheme.typography.title)
                    HbText(
                        stringResource(Res.string.studio_description),
                        color = HbTheme.colors.textSecondary,
                        style = HbTheme.typography.body.copy(textAlign = TextAlign.Center),
                    )
                    HbButton(stringResource(Res.string.studio_back), onBack, Modifier.testTag("studio-back"))
                }
            }
        }
    }
}
