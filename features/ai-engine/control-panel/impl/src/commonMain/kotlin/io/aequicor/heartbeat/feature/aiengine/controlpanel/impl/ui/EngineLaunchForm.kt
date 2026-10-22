package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineManagementIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.KeyValueUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchDraftUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchOptionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchProblemReasonUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchProblemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.RuntimePreferenceUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_add_override
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_add_variable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_custom
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_defaults
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_discard
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_edit
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_environment
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_executable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_executable_placeholder
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_home
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_home_placeholder
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_home_plain
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_key
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_overrides
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_remove
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_reset
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_runtime
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_runtime_custom
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_save
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_title
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_unsaved
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_value
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_launch_variable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_control
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_duplicate
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_invalid_key
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_invalid_name
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_invalid_version
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_not_absolute
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_not_directory
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_not_exe
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_not_executable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_not_found
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_reserved
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_script
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_secret
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_too_long
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_problem_unsupported
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime_auto
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime_host_app
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime_managed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime_system
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime_version
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.engine_runtime_version_newest
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Launch settings of the selected engine. Shown as a summary until edited; the draft is checked as it is typed and
 * cannot be saved with errors. File warnings of the saved settings never block saving. Values are user paths and
 * environment entries; secrets belong in connections and are rejected here.
 */
@Composable
internal fun EngineLaunchForm(
    launch: LaunchUi,
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
) {
    val draft = launch.draft
    HbColumn(modifier.fillMaxWidth().testTag("engine-launch"), gap = HbTheme.spacing.s) {
        val summary = stringResource(
            when {
                launch.isEditing && launch.isDirty -> Res.string.engine_launch_unsaved
                launch.isCustomized -> Res.string.engine_launch_custom
                else -> Res.string.engine_launch_defaults
            },
        )
        HbSettingsRow(stringResource(Res.string.engine_launch_title), description = summary) {
            if (!launch.isEditing) {
                ActionButton(
                    stringResource(Res.string.engine_launch_edit),
                    "engine-launch-edit",
                    isIdle,
                    visualEnabled = isVisuallyIdle,
                ) {
                    onIntent(EngineManagementIntent.EditLaunch(draft))
                }
            }
        }
        if (launch.isEditing) {
            LaunchEditor(
                launch,
                isIdle,
                onIntent,
                Modifier.padding(horizontal = HbTheme.spacing.m),
                isVisuallyIdle = isVisuallyIdle,
            )
        } else {
            Problems(launch.warnings, Modifier.padding(horizontal = HbTheme.spacing.m), HbTone.Warning)
        }
    }
}

@Composable
private fun LaunchEditor(
    launch: LaunchUi,
    isIdle: Boolean,
    onIntent: (EngineManagementIntent) -> Unit,
    modifier: Modifier = Modifier,
    isVisuallyIdle: Boolean = isIdle,
) {
    val draft = launch.draft
    val edit: (LaunchDraftUi) -> Unit = { onIntent(EngineManagementIntent.EditLaunch(it)) }
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
        LaunchPaths(launch, edit)
        if (LaunchOptionUi.Runtime in launch.options && launch.runtimes.isNotEmpty()) {
            RuntimeChoice(launch, edit)
        }
        if (LaunchOptionUi.ConfigOverrides in launch.options) {
            EntryList(
                stringResource(Res.string.engine_launch_overrides),
                stringResource(Res.string.engine_launch_key),
                stringResource(Res.string.engine_launch_add_override),
                draft.configOverrides,
                { edit(draft.copy(configOverrides = it)) },
                "engine-launch-override",
                launch.errors.of(LaunchOptionUi.ConfigOverrides),
            )
        }
        if (LaunchOptionUi.Environment in launch.options) {
            EntryList(
                stringResource(Res.string.engine_launch_environment),
                stringResource(Res.string.engine_launch_variable),
                stringResource(Res.string.engine_launch_add_variable),
                draft.environment,
                { edit(draft.copy(environment = it)) },
                "engine-launch-env",
                launch.errors.of(LaunchOptionUi.Environment),
            )
        }
        HbFlowRow {
            ActionButton(
                stringResource(Res.string.engine_launch_save),
                "engine-launch-save",
                enabled = isIdle && launch.errors.isEmpty() && launch.isDirty,
                visualEnabled = isVisuallyIdle && launch.errors.isEmpty() && launch.isDirty,
                style = HbButtonStyle.Primary,
            ) { onIntent(EngineManagementIntent.SaveLaunch) }
            ActionButton(stringResource(Res.string.engine_launch_discard), "engine-launch-discard", enabled = true) {
                onIntent(EngineManagementIntent.DiscardLaunch)
            }
            ActionButton(stringResource(Res.string.engine_launch_reset), "engine-launch-reset", enabled = true) {
                onIntent(EngineManagementIntent.ResetLaunch)
            }
        }
    }
}

@Composable
private fun LaunchPaths(launch: LaunchUi, edit: (LaunchDraftUi) -> Unit) {
    val draft = launch.draft
    if (LaunchOptionUi.Executable in launch.options) {
        PathField(
            stringResource(Res.string.engine_launch_executable),
            draft.executable,
            { edit(draft.copy(executable = it)) },
            stringResource(Res.string.engine_launch_executable_placeholder),
            "engine-launch-executable",
            launch.errors.of(LaunchOptionUi.Executable),
        )
    }
    if (LaunchOptionUi.HomeDirectory in launch.options) {
        PathField(
            launch.homeVariable?.let { stringResource(Res.string.engine_launch_home, it) }
                ?: stringResource(Res.string.engine_launch_home_plain),
            draft.homeDirectory,
            { edit(draft.copy(homeDirectory = it)) },
            stringResource(Res.string.engine_launch_home_placeholder),
            "engine-launch-home",
            launch.errors.of(LaunchOptionUi.HomeDirectory),
        )
    }
}

/**
 * Which installation the engine runs: automatic, Heartbeat's copy, the host application's runtime (newest or a pinned
 * version) or a system installation. A custom executable wins over the choice; the hint says so.
 */
@Composable
private fun RuntimeChoice(launch: LaunchUi, edit: (LaunchDraftUi) -> Unit, modifier: Modifier = Modifier) {
    val draft = launch.draft
    val choices = listOf(RuntimePreferenceUi.Auto) + RuntimePreferenceUi.entries.filter { it in launch.runtimes }
    HbColumn(modifier.fillMaxWidth().testTag("engine-launch-runtime"), gap = HbTheme.spacing.xs) {
        HbText(stringResource(Res.string.engine_launch_runtime), style = HbTheme.typography.label)
        HbColumn(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.none) {
            choices.forEach { choice ->
                HbChoiceRow(
                    runtimeLabel(choice, launch.hostApp),
                    selected = draft.runtime == choice,
                    isMultiple = false,
                    onClick = { edit(draft.copy(runtime = choice)) },
                    modifier = Modifier.testTag("engine-runtime:${choice.name}"),
                )
            }
        }
        if (draft.executable.isNotBlank()) {
            HbText(
                stringResource(Res.string.engine_launch_runtime_custom),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        if (draft.runtime == RuntimePreferenceUi.HostApp) HostRuntimeVersion(launch, edit)
        Problems(launch.errors.of(LaunchOptionUi.Runtime))
    }
}

/** The pinned version of the host application's runtime: the newest one, or one of the versions it keeps. */
@Composable
private fun HostRuntimeVersion(launch: LaunchUi, edit: (LaunchDraftUi) -> Unit) {
    val draft = launch.draft
    // A pinned version the app no longer keeps stays selectable, so saving never drops it silently.
    val versions = (launch.hostVersions + listOfNotNull(draft.hostRuntimeVersion.takeIf { it.isNotBlank() }))
        .distinct()
    HbText(stringResource(Res.string.engine_runtime_version), style = HbTheme.typography.label)
    HbColumn(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.none) {
        HbChoiceRow(
            stringResource(Res.string.engine_runtime_version_newest),
            selected = draft.hostRuntimeVersion.isBlank(),
            isMultiple = false,
            onClick = { edit(draft.copy(hostRuntimeVersion = "")) },
            modifier = Modifier.testTag("engine-runtime-version:newest"),
        )
        versions.forEach { version ->
            HbChoiceRow(
                version,
                selected = draft.hostRuntimeVersion == version,
                isMultiple = false,
                onClick = { edit(draft.copy(hostRuntimeVersion = version)) },
                modifier = Modifier.testTag("engine-runtime-version:$version"),
            )
        }
    }
}

@Composable
private fun runtimeLabel(choice: RuntimePreferenceUi, hostApp: String?): String = when (choice) {
    RuntimePreferenceUi.Auto -> stringResource(Res.string.engine_runtime_auto)
    RuntimePreferenceUi.Managed -> stringResource(Res.string.engine_runtime_managed)
    RuntimePreferenceUi.HostApp -> stringResource(Res.string.engine_runtime_host_app, hostApp.orEmpty())
    RuntimePreferenceUi.System -> stringResource(Res.string.engine_runtime_system)
}

@Composable
private fun PathField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    problems: ImmutableList<LaunchProblemUi>,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbText(label, style = HbTheme.typography.label)
        HbTextField(
            value,
            onValueChange,
            Modifier.fillMaxWidth().testTag(tag),
            placeholder = placeholder,
            accessibleLabel = label,
        )
        Problems(problems)
    }
}

@Composable
private fun EntryList(
    label: String,
    keyLabel: String,
    addLabel: String,
    entries: ImmutableList<KeyValueUi>,
    onChange: (ImmutableList<KeyValueUi>) -> Unit,
    tag: String,
    problems: ImmutableList<LaunchProblemUi>,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbText(label, style = HbTheme.typography.label)
        val valueLabel = stringResource(Res.string.engine_launch_value)
        entries.forEachIndexed { index, entry ->
            HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
                HbTextField(
                    entry.key,
                    { key -> onChange(entries.replaced(index, entry.copy(key = key))) },
                    Modifier.weight(1f).testTag("$tag-key:$index"),
                    placeholder = keyLabel,
                    accessibleLabel = "$label: $keyLabel ${index + 1}",
                )
                HbTextField(
                    entry.value,
                    { value -> onChange(entries.replaced(index, entry.copy(value = value))) },
                    Modifier.weight(1f).testTag("$tag-value:$index"),
                    placeholder = valueLabel,
                    accessibleLabel = "$label: $valueLabel ${index + 1}",
                )
                HbIconButton(
                    HbIcons.Trash,
                    stringResource(Res.string.engine_launch_remove, index + 1),
                    { onChange(entries.filterIndexed { at, _ -> at != index }.toImmutableList()) },
                    Modifier.testTag("$tag-remove:$index"),
                )
            }
            Problems(problems.filter { it.index == index }.toImmutableList())
        }
        Problems(problems.filter { it.index == null }.toImmutableList())
        ActionButton(addLabel, "$tag-add", enabled = true) {
            onChange((entries + KeyValueUi()).toImmutableList())
        }
    }
}

/**
 * Messages of [problems] as banners: errors block saving ([HbTone.Danger]); file warnings do not ([HbTone.Warning]).
 */
@Composable
private fun Problems(
    problems: ImmutableList<LaunchProblemUi>,
    modifier: Modifier = Modifier,
    tone: HbTone = HbTone.Danger,
) {
    if (problems.isEmpty()) return
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxs) {
        problems.map { it.reason }.distinct().forEach { reason ->
            HbBanner(stringResource(problemText(reason)), tone = tone)
        }
    }
}

private fun ImmutableList<LaunchProblemUi>.of(option: LaunchOptionUi): ImmutableList<LaunchProblemUi> =
    filter { it.option == option }.toImmutableList()

private fun ImmutableList<KeyValueUi>.replaced(index: Int, entry: KeyValueUi): ImmutableList<KeyValueUi> =
    mapIndexed { at, current -> if (at == index) entry else current }.toImmutableList()

private fun problemText(reason: LaunchProblemReasonUi): StringResource = ProblemTexts.getValue(reason)

/** Message of each launch setting problem; the panel tests check that every reason has one. */
internal val ProblemTexts: Map<LaunchProblemReasonUi, StringResource> = mapOf(
    LaunchProblemReasonUi.Unsupported to Res.string.engine_problem_unsupported,
    LaunchProblemReasonUi.NotAbsolute to Res.string.engine_problem_not_absolute,
    LaunchProblemReasonUi.NotExe to Res.string.engine_problem_not_exe,
    LaunchProblemReasonUi.ScriptWrapper to Res.string.engine_problem_script,
    LaunchProblemReasonUi.InvalidName to Res.string.engine_problem_invalid_name,
    LaunchProblemReasonUi.InvalidKey to Res.string.engine_problem_invalid_key,
    LaunchProblemReasonUi.Reserved to Res.string.engine_problem_reserved,
    LaunchProblemReasonUi.Secret to Res.string.engine_problem_secret,
    LaunchProblemReasonUi.Duplicate to Res.string.engine_problem_duplicate,
    LaunchProblemReasonUi.TooLong to Res.string.engine_problem_too_long,
    LaunchProblemReasonUi.ControlCharacter to Res.string.engine_problem_control,
    LaunchProblemReasonUi.NotFound to Res.string.engine_problem_not_found,
    LaunchProblemReasonUi.NotExecutable to Res.string.engine_problem_not_executable,
    LaunchProblemReasonUi.NotADirectory to Res.string.engine_problem_not_directory,
    LaunchProblemReasonUi.InvalidVersion to Res.string.engine_problem_invalid_version,
)
