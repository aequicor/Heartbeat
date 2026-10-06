package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbQuestionnaireTheme
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A pending question's dark oak attention surface, shared by questionnaires and agent permission requests.
 * Foundation layout and Hb controls keep the same treatment across platform kits. The host supplies localized
 * [attentionLabel], [title] and answer controls in [content], and owns all answer state and delivery.
 * [questionId] identifies the request: changing it restarts the finite entrance signal, unless motion is reduced.
 * Only the title is announced; long reviewed content belongs in a bounded, scrollable area inside [content].
 */
@Composable
fun HbQuestionnaireCard(
    questionId: String,
    title: String,
    attentionLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    HbQuestionnaireTheme {
        val motion = HbTheme.motion
        val entrance = remember(questionId) { Animatable(if (motion.isReducedMotion) 1f else 0f) }
        val attention = remember(questionId) { Animatable(0f) }
        LaunchedEffect(questionId, motion) {
            if (motion.isReducedMotion) {
                entrance.snapTo(1f)
                attention.snapTo(0f)
            } else {
                coroutineScope {
                    launch { entrance.animateTo(1f, tween(motion.slowMillis)) }
                    repeat(motion.questionnairePulseCount) {
                        attention.animateTo(1f, tween(motion.questionnairePulseMillis))
                        attention.animateTo(0f, tween(motion.questionnairePulseMillis))
                    }
                }
            }
        }
        val colors = HbTheme.colors
        val entryOffset = HbTheme.spacing.l
        HbColumn(
            modifier.fillMaxWidth().testTag("question-$questionId")
                .graphicsLayer { translationY = entryOffset.toPx() * (1f - entrance.value) }
                .background(colors.surface, HbTheme.shapes.medium)
                .border(
                    HbTheme.dimensions.borderWidth,
                    lerp(colors.outlineSubtle, colors.primary, attention.value),
                    HbTheme.shapes.medium,
                )
                .clip(HbTheme.shapes.medium),
            gap = HbTheme.spacing.none,
        ) {
            HbRow(
                Modifier.fillMaxWidth().height(HbTheme.dimensions.questionnaireAccentHeight)
                    .background(lerp(colors.primary, colors.textPrimary, attention.value))
                    .testTag("question-accent-$questionId"),
            ) {}
            HbColumn(Modifier.padding(HbTheme.spacing.l), gap = HbTheme.spacing.m) {
                HbRow(
                    Modifier.testTag("question-attention-$questionId"),
                    gap = HbTheme.spacing.s,
                ) {
                    HbIcon(HbIcons.Chat, contentDescription = null, tint = colors.primary)
                    HbText(
                        attentionLabel,
                        style = HbTheme.typography.caption,
                        color = colors.primary,
                    )
                }
                HbText(
                    title,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = HbTheme.typography.label,
                )
                content()
            }
        }
    }
}
