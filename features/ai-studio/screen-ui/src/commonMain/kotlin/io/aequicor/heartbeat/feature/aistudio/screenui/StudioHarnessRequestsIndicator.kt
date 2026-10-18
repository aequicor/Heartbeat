package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_requests_label
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_requests_title
import org.jetbrains.compose.resources.stringResource

/** A compact entry to the library; decisions are made only on the request cards inside the owning feature. */
@Composable
internal fun HarnessRequestsIndicator(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.harness_requests_title, count)
    HbTooltip(description) {
        HbButton(
            stringResource(Res.string.harness_requests_label, count),
            onOpen,
            modifier.testTag("sidebar-harness-requests").semantics { contentDescription = description },
            style = HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
    }
}
