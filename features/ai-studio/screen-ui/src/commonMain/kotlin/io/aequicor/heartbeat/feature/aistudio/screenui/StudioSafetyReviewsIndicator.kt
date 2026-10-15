package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_abandoned
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_busy
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_finishing
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_label
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_label_queued
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_queued
import io.aequicor.heartbeat.feature.aistudio.impl.resources.safety_reviews_title
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SafetyReviewsUi
import org.jetbrains.compose.resources.stringResource

/**
 * Quiet sidebar header status of automatic safety reviews: taken slots of the capacity and, when reviews wait,
 * the queue. Its tooltip and accessible description repeat the label and spell out taken slots, queued, stopping and
 * abandoned reviews; the visible label is not announced twice.
 */
@Composable
internal fun SafetyReviewsIndicator(reviews: SafetyReviewsUi, modifier: Modifier = Modifier) {
    val label = if (reviews.queued > 0) {
        stringResource(Res.string.safety_reviews_label_queued, reviews.busy, reviews.capacity, reviews.queued)
    } else {
        stringResource(Res.string.safety_reviews_label, reviews.busy, reviews.capacity)
    }
    val description = reviews.description(label)
    HbTooltip(description, modifier) {
        HbRow(
            Modifier.testTag("sidebar-safety-reviews").clearAndSetSemantics { contentDescription = description },
            gap = HbTheme.spacing.xs,
        ) {
            HbIcon(HbIcons.Shield, contentDescription = null)
            HbText(label, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun SafetyReviewsUi.description(label: String): String = listOfNotNull(
    stringResource(Res.string.safety_reviews_title, label),
    stringResource(Res.string.safety_reviews_busy, busy, capacity),
    queued.takeIf { it > 0 }?.let { stringResource(Res.string.safety_reviews_queued, it) },
    finishing.takeIf { it > 0 }?.let { stringResource(Res.string.safety_reviews_finishing, it) },
    abandoned.takeIf { it > 0 }?.let { stringResource(Res.string.safety_reviews_abandoned, it) },
).joinToString("\n")
