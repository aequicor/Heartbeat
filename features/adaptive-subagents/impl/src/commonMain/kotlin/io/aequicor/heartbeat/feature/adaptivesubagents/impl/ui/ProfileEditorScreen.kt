package io.aequicor.heartbeat.feature.adaptivesubagents.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.AxisUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.EditorErrorUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ProfileEditorIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ProfileEditorModel
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ProfileEditorState
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.RatingChoicesUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.TraitListUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.Res
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_base
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_configuration
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_cost
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_delete
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_effort
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_error_conflict
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_error_invalid
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_error_limit
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_error_save
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_quality
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_rating_menu
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_researched
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_save
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_separate
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_separate_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_sources
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_speed
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_strengths
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_summary
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_tags
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_title
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_use_cases
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.editor_weaknesses
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_back
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_dismiss
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_loading
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** The description editor of one model. */
@Composable
internal fun ProfileEditorScreen(model: ProfileEditorModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by produceState(ProfileEditorState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    ProfileEditorContent(state, model.store::intent, onBack, modifier)
}

/** Stateless editor: the configuration chooser, ratings, lists, summary, tags and sources. */
@Composable
internal fun ProfileEditorContent(
    state: ProfileEditorState,
    onIntent: (ProfileEditorIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("model-profile-editor"),
        gap = HbTheme.spacing.none,
    ) {
        HbPaneHeader(
            state.title.ifEmpty { stringResource(Res.string.editor_title) },
            navigation = { HbIconButton(HbIcons.ArrowLeft, stringResource(Res.string.subagents_back), onBack) },
        ) {
            HbButton(
                stringResource(Res.string.editor_save),
                { onIntent(ProfileEditorIntent.Save) },
                Modifier.testTag("model-profile-save"),
                enabled = state.isLoaded,
                size = HbButtonSize.Small,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                state.error?.let { error -> item(key = "error") { EditorError(error, onIntent) } }
                if (!state.isLoaded) {
                    item(key = "loading") { HbLoadingState(stringResource(Res.string.subagents_loading)) }
                } else {
                    item(key = "general") { GeneralSection(state, onIntent) }
                    item(key = "configuration") { ConfigurationSection(state, onIntent) }
                    if (state.sources.isNotEmpty()) item(key = "sources") { SourcesSection(state) }
                    if (state.isDescribed) {
                        item(key = "delete") {
                            HbButton(
                                stringResource(Res.string.editor_delete),
                                { onIntent(ProfileEditorIntent.Delete) },
                                Modifier.testTag("model-profile-delete"),
                                style = HbButtonStyle.Danger,
                                size = HbButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralSection(
    state: ProfileEditorState,
    onIntent: (ProfileEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        state.engine,
        modifier,
        description = stringResource(Res.string.editor_researched).takeIf { state.isResearched },
    ) {
        MultilineField(
            state.summary,
            stringResource(Res.string.editor_summary),
            "model-profile-summary",
            { onIntent(ProfileEditorIntent.ChangeSummary(it)) },
        )
        val tags = stringResource(Res.string.editor_tags)
        HbTextField(
            state.tags,
            { onIntent(ProfileEditorIntent.ChangeTags(it)) },
            Modifier.fillMaxWidth().testTag("model-profile-tags"),
            placeholder = tags,
        )
    }
}

@Composable
private fun ConfigurationSection(
    state: ProfileEditorState,
    onIntent: (ProfileEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = stringResource(Res.string.editor_base)
    val efforts = (listOf(BASE_ID to base) + state.efforts.map { it to stringResource(Res.string.editor_effort, it) })
        .toImmutableList()
    HbSettingsSection(
        stringResource(Res.string.editor_configuration),
        modifier.testTag("model-profile-configuration"),
    ) {
        if (state.efforts.isNotEmpty()) {
            HbSettingsRow(stringResource(Res.string.editor_configuration), Modifier.fillMaxWidth()) {
                ChoiceMenu(
                    efforts,
                    state.selected ?: BASE_ID,
                    stringResource(Res.string.editor_configuration),
                    "model-profile-effort",
                    { onIntent(ProfileEditorIntent.SelectConfiguration(it.takeUnless { id -> id == BASE_ID })) },
                )
            }
        }
        val selected = state.selected
        if (selected != null) {
            val separate = stringResource(Res.string.editor_separate)
            HbSettingsRow(
                separate,
                Modifier.fillMaxWidth(),
                description = stringResource(Res.string.editor_separate_hint),
            ) {
                HbSwitch(
                    selected in state.variants,
                    { onIntent(ProfileEditorIntent.SetSeparate(it)) },
                    separate,
                    Modifier.testTag("model-profile-separate"),
                )
            }
        }
        if (selected == null || selected in state.variants) TraitsEditor(state, onIntent)
    }
}

@Composable
private fun TraitsEditor(state: ProfileEditorState, onIntent: (ProfileEditorIntent) -> Unit) {
    val ratings = RatingChoicesUi.map { it.toString() to it.toString() }.toImmutableList()
    AxisUi.entries.forEach { axis ->
        HbDivider()
        HbSettingsRow(stringResource(axis.title()), Modifier.fillMaxWidth()) {
            ChoiceMenu(
                ratings,
                state.current.rating(axis).toString(),
                stringResource(Res.string.editor_rating_menu),
                "model-profile-rating-${axis.name}",
                { onIntent(ProfileEditorIntent.ChangeRating(axis, it.toInt())) },
            )
        }
    }
    TraitListUi.entries.forEach { list ->
        MultilineField(
            state.current.text(list),
            stringResource(list.title()),
            "model-profile-${list.name}",
            { onIntent(ProfileEditorIntent.ChangeList(list, it)) },
        )
    }
}

@Composable
private fun SourcesSection(state: ProfileEditorState, modifier: Modifier = Modifier) {
    HbSettingsSection(stringResource(Res.string.editor_sources), modifier) {
        state.sources.forEach { HbText(it, color = HbTheme.colors.textSecondary, maxLines = 1) }
    }
}

@Composable
private fun MultilineField(
    value: String,
    label: String,
    tag: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbText(label, color = HbTheme.colors.textSecondary)
        HbTextField(
            value,
            onChange,
            Modifier.fillMaxWidth().heightIn(min = HbTheme.dimensions.composerMaxHeight).testTag(tag),
            placeholder = label,
            singleLine = false,
        )
    }
}

@Composable
private fun EditorError(error: EditorErrorUi, onIntent: (ProfileEditorIntent) -> Unit, modifier: Modifier = Modifier) {
    HbBanner(stringResource(error.message()), modifier.testTag("model-profile-error")) {
        HbRow(gap = HbTheme.spacing.s) {
            HbButton(
                stringResource(Res.string.subagents_dismiss),
                { onIntent(ProfileEditorIntent.DismissError) },
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
    }
}

private fun AxisUi.title(): StringResource = when (this) {
    AxisUi.Quality -> Res.string.editor_quality
    AxisUi.Speed -> Res.string.editor_speed
    AxisUi.Cost -> Res.string.editor_cost
}

private fun TraitListUi.title(): StringResource = when (this) {
    TraitListUi.Strengths -> Res.string.editor_strengths
    TraitListUi.Weaknesses -> Res.string.editor_weaknesses
    TraitListUi.UseCases -> Res.string.editor_use_cases
}

private fun EditorErrorUi.message(): StringResource = when (this) {
    EditorErrorUi.Invalid -> Res.string.editor_error_invalid
    EditorErrorUi.Conflict -> Res.string.editor_error_conflict
    EditorErrorUi.Limit -> Res.string.editor_error_limit
    EditorErrorUi.SaveFailed -> Res.string.editor_error_save
}

private const val BASE_ID = "base"
