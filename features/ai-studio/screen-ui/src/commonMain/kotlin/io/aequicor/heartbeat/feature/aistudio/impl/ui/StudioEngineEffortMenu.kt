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
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * Only engine-advertised options are selectable. Levels are shown and round-tripped exactly as the provider names
 * them, never translated: the same identifiers appear in the provider's documentation and CLI.
 */
@Composable
internal fun EngineEffortMenu(
    model: ModelUi,
    selected: String?,
    onIntent: (AiStudioScreenIntent) -> Unit,
    paneId: Int? = null,
    enabled: Boolean = true,
) {
    var isOpen by remember(model.id) { mutableStateOf(false) }
    val automatic = stringResource(Res.string.effort_default)
    val actions = listOf(HbComposerAction(DEFAULT_EFFORT, automatic, isSelected = selected == null)) +
        model.reasoningEfforts.map {
            HbComposerAction("$EFFORT_PREFIX$it", it, isSelected = selected == it)
        }
    HbComposerMenuButton(
        label = selected ?: automatic,
        actions = actions.toImmutableList(),
        isExpanded = isOpen,
        onExpandedChange = { isOpen = it },
        onAction = {
            val effort = if (it == DEFAULT_EFFORT) null else it.removePrefix(EFFORT_PREFIX)
            onIntent(AiStudioScreenIntent.SelectEngineEffort(model.id, effort, paneId))
        },
        modifier = Modifier.testTag("effort-chip"),
        accessibleLabel = stringResource(Res.string.composer_effort_menu),
        headerLabel = stringResource(Res.string.composer_effort_menu),
        icon = HbIcons.Sparkles,
        style = HbComposerMenuStyle.AccentPill,
        enabled = enabled,
    )
}

private const val DEFAULT_EFFORT = "default"
private const val EFFORT_PREFIX = "effort:"
