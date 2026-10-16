package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineStatusUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_back
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_load_failed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_retry
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_status_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_status_on
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_status_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * A settings column of the panel: an optional header with a back action (outside the settings window or in a
 * drill-down), then [content] centered at the settings width.
 */
@Composable
internal fun ControlColumn(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    HbColumn(modifier.fillMaxSize().background(HbTheme.surfaces.backdrop), gap = HbTheme.spacing.none) {
        if (title != null && onBack != null) ControlHeader(title, onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxWidth()
                    .hbVerticalScroll(rememberScrollState())
                    .padding(HbTheme.spacing.xl),
                gap = HbTheme.spacing.xxl,
                content = content,
            )
        }
    }
}

@Composable
private fun ControlHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    HbPaneHeader(
        title,
        modifier,
        leadingInset = if (HbTheme.dimensions.isDesktop) HbTheme.spacing.m else HbTheme.dimensions.titlebarLeadingInset,
        navigation = {
            HbIconButton(
                HbIcons.ArrowLeft,
                stringResource(Res.string.control_back),
                onBack,
                Modifier.testTag("control-back"),
            )
        },
    )
}

/** Badge of an engine's status. */
@Composable
internal fun StatusBadge(status: EngineStatusUi, modifier: Modifier = Modifier) {
    when (status) {
        EngineStatusUi.On -> HbBadge(stringResource(Res.string.control_status_on), modifier, HbTone.Success)

        EngineStatusUi.Off -> HbBadge(stringResource(Res.string.control_status_off), modifier)

        EngineStatusUi.Unavailable -> HbBadge(
            stringResource(Res.string.control_status_unavailable),
            modifier,
            HbTone.Warning,
        )
    }
}

/** A failed observation with a retry action. */
@Composable
internal fun LoadFailure(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    HbBanner(stringResource(Res.string.control_load_failed), modifier) {
        HbButton(
            stringResource(Res.string.control_retry),
            onRetry,
            style = HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
    }
}

/** The engine-supplied text in the language of the device, which also selects the string resources. */
internal fun TextUi.text(): String = resolve(Locale.current.language)
