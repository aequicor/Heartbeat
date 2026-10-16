package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFieldUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigPageUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigSectionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldEditorUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_advanced_hide
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_advanced_show
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_blocking
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_discard
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_other_problems
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_save
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_saving
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_unsaved
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_config_conflict
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** One configuration page: problems outside its fields, then its sections; the save bar is pinned by the host. */
@Composable
internal fun ConfigPageContent(
    page: ConfigPageUi,
    form: ConfigFormUi,
    isSaving: Boolean,
    onIntent: (ConfigIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth().testTag("config-page:${page.id}"), gap = HbTheme.spacing.xxl) {
        if (form.otherProblems.any { it.isBlocking }) {
            HbBanner(stringResource(Res.string.control_config_other_problems))
        }
        ConfigPageSections(page, isEditable = !isSaving && !form.isSubmitted, onIntent)
    }
}

/** The sections of [page]; advanced fields stay folded unless they are set or have problems. */
@Composable
internal fun ConfigPageSections(
    page: ConfigPageUi,
    isEditable: Boolean,
    onIntent: (ConfigIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isAdvancedShown by remember(page.id) { mutableStateOf(false) }
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxl) {
        page.sections.forEach { section ->
            val fields = section.fields.filter {
                isAdvancedShown || !it.isAdvanced || it.isSet || it.problems.isNotEmpty()
            }
            if (fields.isNotEmpty()) Section(section, fields.toImmutableList(), isEditable, isAdvancedShown, onIntent)
        }
        if (page.sections.any { section -> section.fields.any { it.hasAdvanced() } }) {
            val toggle = if (isAdvancedShown) {
                Res.string.control_config_advanced_hide
            } else {
                Res.string.control_config_advanced_show
            }
            HbButton(
                stringResource(toggle),
                { isAdvancedShown = !isAdvancedShown },
                Modifier.testTag("config-advanced:${page.id}"),
                HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
    }
}

/**
 * Unsaved changes with save and discard, or a conflict that only a discard resolves. [isBusy] holds the save while
 * another operation, such as a resource read, runs without claiming the changes are being saved.
 */
@Composable
internal fun ConfigSaveBar(
    form: ConfigFormUi,
    isSaving: Boolean,
    onIntent: (ConfigIntent) -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = isSaving,
) {
    val discard = stringResource(Res.string.control_config_discard)
    when {
        form.hasConflict -> HbBanner(
            stringResource(Res.string.control_failure_config_conflict),
            modifier.testTag("config-conflict"),
            HbTone.Warning,
        ) {
            HbButton(
                discard,
                { onIntent(ConfigIntent.DiscardConfig) },
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }

        form.isDirty -> HbRow(modifier.fillMaxWidth().testTag("config-save-bar"), gap = HbTheme.spacing.s) {
            val status = when {
                form.isSubmitted || isSaving -> stringResource(Res.string.control_config_saving)
                form.blockingProblems > 0 -> stringResource(Res.string.control_config_blocking, form.blockingProblems)
                else -> stringResource(Res.string.control_config_unsaved)
            }
            HbText(
                status,
                Modifier.weight(1f),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
            HbButton(
                discard,
                { onIntent(ConfigIntent.DiscardConfig) },
                Modifier.testTag("config-discard"),
                HbButtonStyle.Ghost,
                enabled = !form.isSubmitted,
                size = HbButtonSize.Small,
            )
            HbButton(
                stringResource(Res.string.control_config_save),
                { onIntent(ConfigIntent.SaveConfig) },
                Modifier.testTag("config-save"),
                HbButtonStyle.Primary,
                enabled = form.isSaveable && !isBusy,
                size = HbButtonSize.Small,
            )
        }
    }
}

@Composable
private fun Section(
    section: ConfigSectionUi,
    fields: ImmutableList<ConfigFieldUi>,
    isEditable: Boolean,
    isAdvancedShown: Boolean,
    onIntent: (ConfigIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        section.title.text(),
        modifier.testTag("config-section:${section.id}"),
        description = section.note?.text(),
    ) {
        fields.forEachIndexed { index, field ->
            if (index > 0) HbDivider()
            ConfigFieldRow(
                field,
                isEditable,
                { onIntent(ConfigIntent.EditField(field.id, it)) },
                isAdvancedShown = isAdvancedShown,
            )
        }
    }
}

/** The field or one of its entries' fields is advanced. */
private fun ConfigFieldUi.hasAdvanced(): Boolean =
    isAdvanced || (editor as? FieldEditorUi.Entries)?.items?.any { entry -> entry.fields.any { it.isAdvanced } } == true
