package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.notice_dismiss
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioNoticeUi
import org.jetbrains.compose.resources.stringResource

/** A transient pane message; dismissing it changes presentation without acting on the conversation. */
@Composable
internal fun StudioNotice(
    content: PaneContent,
    notice: StudioNoticeUi,
    message: String,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
    tone: HbTone = HbTone.Neutral,
) {
    if (notice in content.dismissedNotices) return
    HbBanner(message, modifier.testTag("notice-${notice.name}-${content.pane.id}"), tone = tone) {
        HbIconButton(
            HbIcons.Close,
            contentDescription = stringResource(Res.string.notice_dismiss),
            onClick = { onIntent(AiStudioScreenIntent.DismissNotice(content.pane.id, notice)) },
            modifier = Modifier.testTag("dismiss-notice-${notice.name}-${content.pane.id}"),
        )
    }
}
