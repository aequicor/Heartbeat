package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbComposerUsageButton
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Interactive usage examples cover empty, partial, exhausted, quotas-only and disabled controls. */
@Composable
internal fun UsageCatalog(modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val title = hbString(HbString.UsageTitle)
    val limits = hbString(HbString.UsageLimits)
    HbCard(modifier.fillMaxWidth().testTag("usage-catalog")) {
        HbText(title, style = HbTheme.typography.title)
        HbFlowRow {
            listOf(0, 61, 100, null).forEachIndexed { index, percent ->
                HbComposerUsageButton(
                    percent,
                    limits,
                    selected == index,
                    { isOpen -> selected = if (isOpen) index else null },
                    accessibleLabel = title,
                ) { UsageCatalogDetails(percent) }
            }
            HbComposerUsageButton(
                61,
                limits,
                false,
                {},
                enabled = false,
                accessibleLabel = hbString(HbString.Disabled),
            ) { }
        }
        HbProgressBar(0f)
        HbProgressBar(0.61f)
        HbProgressBar(1f, color = HbTheme.colors.warning)
    }
}

@Composable
private fun UsageCatalogDetails(percent: Int?) {
    if (percent != null) {
        HbRow {
            HbText(hbString(HbString.UsageContext), Modifier.weight(1f))
            HbText("$percent%")
        }
        HbProgressBar(percent / 100f)
        HbDivider()
    }
    HbRow {
        HbText(hbString(HbString.UsageWeekly), Modifier.weight(1f))
        HbText("99%")
    }
    HbProgressBar(0.99f, color = HbTheme.colors.warning)
    HbText(
        hbString(HbString.UsageResetExample),
        style = HbTheme.typography.caption,
        color = HbTheme.colors.textSecondary,
    )
}
