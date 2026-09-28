package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbComposerAction
import io.aequicor.heartbeat.ds.components.HbComposerMenuButton
import io.aequicor.heartbeat.ds.components.HbComposerMenuStyle
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_effort_menu
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_default
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_high
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_low
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_medium
import io.aequicor.heartbeat.feature.aistudio.impl.resources.effort_very_high
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** Only engine-advertised options are selectable; native identifiers are round-tripped unchanged. */
@Composable
internal fun EngineEffortMenu(model: ModelUi, selected: String?, onIntent: (AiStudioScreenIntent) -> Unit) {
    var isOpen by remember(model.id) { mutableStateOf(false) }
    val automatic = stringResource(Res.string.effort_default)
    val actions = listOf(HbComposerAction(DEFAULT_EFFORT, automatic, isSelected = selected == null)) +
        model.reasoningEfforts.map {
            HbComposerAction("$EFFORT_PREFIX$it", nativeEffortLabel(it), isSelected = selected == it)
        }
    HbComposerMenuButton(
        label = (selected ?: model.defaultReasoningEffort)?.let { nativeEffortLabel(it) } ?: automatic,
        actions = actions.toImmutableList(),
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = {
            val effort = if (it == DEFAULT_EFFORT) null else it.removePrefix(EFFORT_PREFIX)
            onIntent(AiStudioScreenIntent.SelectEngineEffort(model.id, effort))
        },
        modifier = Modifier.testTag("effort-chip"),
        accessibleLabel = stringResource(Res.string.composer_effort_menu),
        headerLabel = stringResource(Res.string.composer_effort_menu),
        icon = HbIcons.Sparkles,
        style = HbComposerMenuStyle.AccentPill,
    )
}

/** Familiar budgets are localized; new provider identifiers remain visible instead of being guessed or discarded. */
@Composable
private fun nativeEffortLabel(id: String): String = when (id) {
    "low" -> stringResource(Res.string.effort_low)
    "medium" -> stringResource(Res.string.effort_medium)
    "high" -> stringResource(Res.string.effort_high)
    "xhigh" -> stringResource(Res.string.effort_very_high)
    else -> id
}

private const val DEFAULT_EFFORT = "default"
private const val EFFORT_PREFIX = "effort:"
