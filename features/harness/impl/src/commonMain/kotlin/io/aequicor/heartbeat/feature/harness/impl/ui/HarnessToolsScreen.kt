package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.EngineToolsUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessToolsIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessToolsModel
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessToolsState
import io.aequicor.heartbeat.feature.harness.impl.presentation.HostedGroupUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.HostedToolUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.NativeChoiceUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.NativeToolUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.harness.impl.resources.Res
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_dismiss
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_hooks_hosted
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_hooks_native
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_hooks_none
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_hosted_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_hosted_section
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_native_default_off
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_native_default_on
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_native_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_native_off
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_native_on
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_native_only_off
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_native_toggle_off
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_save_failed
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_tool_available
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_tools_title
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Tool policy of one harness: Heartbeat tools to turn off and native engine switches. */
@Composable
internal fun HarnessToolsScreen(model: HarnessToolsModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by produceState(HarnessToolsState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    HarnessToolsContent(state, model.store::intent, onBack, modifier)
}

/** Stateless tool policy screen. */
@Composable
internal fun HarnessToolsContent(
    state: HarnessToolsState,
    onIntent: (HarnessToolsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HarnessPane(stringResource(Res.string.harness_tools_title, state.harnessName), "harness-tools", onBack, modifier) {
        if (state.isSaveFailed) {
            item(key = "error") {
                HbBanner(stringResource(Res.string.harness_save_failed), Modifier.testTag("harness-tools-error")) {
                    HbButton(
                        stringResource(Res.string.harness_dismiss),
                        { onIntent(HarnessToolsIntent.DismissError) },
                        style = HbButtonStyle.Secondary,
                        size = HbButtonSize.Small,
                    )
                }
            }
        }
        if (!phaseItems(state.phase)) return@HarnessPane
        if (!state.isNativeEnablingAvailable) {
            item(key = "native-flag") {
                HbBanner(stringResource(Res.string.harness_native_toggle_off), tone = HbTone.Neutral)
            }
        }
        state.engines.forEach { engine ->
            item(key = "engine-${engine.id}") { EngineSection(engine, state.isNativeEnablingAvailable, onIntent) }
        }
        item(key = "hosted-title") {
            HbColumn(gap = HbTheme.spacing.xxs) {
                HbText(
                    stringResource(Res.string.harness_hosted_section),
                    Modifier.semantics { heading() },
                    style = HbTheme.typography.label,
                )
                HbText(
                    stringResource(Res.string.harness_hosted_hint),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
            }
        }
        state.groups.forEach { group ->
            item(key = "group-${group.id}") { HostedSection(group, onIntent) }
        }
    }
}

@Composable
private fun HostedSection(group: HostedGroupUi, onIntent: (HarnessToolsIntent) -> Unit, modifier: Modifier = Modifier) {
    HbSettingsSection(group.title, modifier.testTag("harness-hosted-${group.id}")) {
        group.tools.forEachIndexed { index, tool ->
            key(tool.name) {
                if (index > 0) HbDivider()
                HbSettingsRow(tool.name, Modifier.fillMaxWidth()) {
                    HbSwitch(
                        !tool.isOff,
                        { onIntent(HarnessToolsIntent.SetHostedOff(tool.name, !it)) },
                        stringResource(Res.string.harness_tool_available, tool.name),
                        Modifier.testTag("harness-hosted-tool-${tool.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineSection(
    engine: EngineToolsUi,
    isNativeEnablingAvailable: Boolean,
    onIntent: (HarnessToolsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverage = stringResource(
        if (engine.areNativeCallsHooked) Res.string.harness_hooks_native else Res.string.harness_hooks_hosted,
    )
    HbSettingsSection(
        engine.title,
        modifier.testTag("harness-engine-${engine.id}"),
        description = listOf(stringResource(Res.string.harness_native_hint), coverage).joinToString(" "),
    ) {
        if (engine.tools.isEmpty()) {
            HbSettingsRow(stringResource(Res.string.harness_hooks_none), Modifier.fillMaxWidth())
        }
        engine.tools.forEachIndexed { index, tool ->
            key(tool.name) {
                if (index > 0) HbDivider()
                NativeRow(engine.id, tool, isNativeEnablingAvailable, onIntent)
            }
        }
    }
}

@Composable
private fun NativeRow(
    engine: String,
    tool: NativeToolUi,
    isNativeEnablingAvailable: Boolean,
    onIntent: (HarnessToolsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(
        if (tool.isEnabledByDefault) Res.string.harness_native_default_on else Res.string.harness_native_default_off,
    )
    val labels = mapOf(
        NativeChoiceUi.Default to defaultLabel,
        NativeChoiceUi.On to stringResource(Res.string.harness_native_on),
        NativeChoiceUi.Off to stringResource(Res.string.harness_native_off),
    )
    val isOnAllowed = tool.isOnAllowed && isNativeEnablingAvailable
    val choiceDescription = "${tool.name}: ${labels.getValue(tool.choice)}"
    HbSettingsRow(
        tool.name,
        modifier.fillMaxWidth().testTag("harness-native-$engine-${tool.name}"),
        description = if (tool.isOnAllowed) null else stringResource(Res.string.harness_native_only_off),
    ) {
        Box {
            HbButton(
                labels.getValue(tool.choice),
                { isExpanded = true },
                Modifier.semantics { contentDescription = choiceDescription }
                    .testTag("harness-native-choice-$engine-${tool.name}"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
            HbMenu(
                NativeChoiceUi.entries.map { choice ->
                    HbMenuItem(
                        choice.name,
                        labels.getValue(choice),
                        isEnabled = choice != NativeChoiceUi.On || isOnAllowed,
                        isChecked = choice == tool.choice,
                    )
                }.toImmutableList(),
                isExpanded,
                { isExpanded = false },
                { id ->
                    isExpanded = false
                    val choice = NativeChoiceUi.valueOf(id)
                    onIntent(HarnessToolsIntent.SetNative(engine, tool.name, choice))
                },
                tool.name,
            )
        }
    }
}

private val previewState = HarnessToolsState(
    phase = PhaseUi.Ready,
    harnessName = "compose_ui",
    groups = persistentListOf(
        HostedGroupUi(
            "search",
            "Поиск",
            persistentListOf(HostedToolUi("web_search", isOff = true), HostedToolUi("web_fetch", isOff = false)),
        ),
    ),
    engines = persistentListOf(
        EngineToolsUi(
            "claude",
            "Claude",
            persistentListOf(
                NativeToolUi("Bash", NativeChoiceUi.On, isEnabledByDefault = false, isOnAllowed = true),
                NativeToolUi("Agent", NativeChoiceUi.Default, isEnabledByDefault = true, isOnAllowed = false),
            ),
            areNativeCallsHooked = true,
        ),
        EngineToolsUi(
            "codex",
            "Codex",
            persistentListOf(NativeToolUi("shell", NativeChoiceUi.Off, isEnabledByDefault = true, isOnAllowed = false)),
            areNativeCallsHooked = false,
        ),
    ),
    isNativeEnablingAvailable = true,
)

@Preview
@Composable
private fun HarnessToolsLightPreview() {
    HbTheme(darkTheme = false) { HarnessToolsContent(previewState, {}, {}) }
}

@Preview
@Composable
private fun HarnessToolsDarkPreview() {
    HbTheme(darkTheme = true) {
        HarnessToolsContent(previewState.copy(isNativeEnablingAvailable = false), {}, {})
    }
}
