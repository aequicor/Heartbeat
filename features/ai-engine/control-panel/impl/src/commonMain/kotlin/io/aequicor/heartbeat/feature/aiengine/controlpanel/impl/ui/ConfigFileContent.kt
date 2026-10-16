package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbCodeEditor
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFileUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_invalid_json
import org.jetbrains.compose.resources.stringResource

/** One configuration file as a whole: keys the pages do not show included; a JSON file must stay an object. */
@Composable
internal fun ConfigFileContent(
    file: ConfigFileUi,
    form: ConfigFormUi,
    isSaving: Boolean,
    onIntent: (ConfigIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth().testTag("config-file:${file.id}"), gap = HbTheme.spacing.l) {
        ConfigSaveBar(form, isSaving, onIntent)
        HbCodeEditor(
            file.text,
            { onIntent(ConfigIntent.EditFile(file.id, it)) },
            file.fileName,
            Modifier.fillMaxWidth().testTag("config-file-editor:${file.id}"),
            language = file.language,
            isReadOnly = isSaving || form.isSubmitted,
            isError = file.isInvalid,
            errorMessage = stringResource(Res.string.control_config_invalid_json).takeIf { file.isInvalid },
        )
    }
}
