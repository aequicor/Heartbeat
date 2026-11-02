package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
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
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineConnectionStatusUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_back
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_engine_connected
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_engine_disconnected
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_load_failed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_retry
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_status_off
import org.jetbrains.compose.resources.stringResource

/**
 * A settings column of the panel: an optional header with optional back and trailing actions, then [content]
 * centered at the settings width.
 */
@Composable
internal fun ControlColumn(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    pinned: (@Composable () -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    HbColumn(modifier.fillMaxSize().background(HbTheme.surfaces.backdrop), gap = HbTheme.spacing.none) {
        if (title != null) ControlHeader(title, onBack, headerActions = headerActions)
        // What must stay reachable while the content scrolls, such as saving a long form.
        pinned?.let {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxWidth()
                        .padding(horizontal = HbTheme.spacing.xl, vertical = HbTheme.spacing.s),
                ) { it() }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxWidth()
                    .hbVerticalScroll(scrollState)
                    .padding(HbTheme.spacing.xl),
                gap = HbTheme.spacing.xxl,
                content = content,
            )
        }
    }
}

@Composable
private fun ControlHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
) {
    HbPaneHeader(
        title,
        modifier,
        leadingInset = if (HbTheme.dimensions.isDesktop) HbTheme.spacing.m else HbTheme.dimensions.titlebarLeadingInset,
        navigation = {
            if (onBack != null) {
                HbIconButton(
                    HbIcons.ArrowLeft,
                    stringResource(Res.string.control_back),
                    onBack,
                    Modifier.testTag("control-back"),
                )
            }
        },
        actions = headerActions ?: {},
    )
}

/** Text of an engine's connection status; only a connected engine gets a check mark. */
@Composable
internal fun EngineConnectionStatus(status: EngineConnectionStatusUi, engineId: String, modifier: Modifier = Modifier) {
    val text = when (status) {
        EngineConnectionStatusUi.Connected -> stringResource(Res.string.control_engine_connected)
        EngineConnectionStatusUi.NotConnected -> stringResource(Res.string.control_engine_disconnected)
        EngineConnectionStatusUi.Off -> stringResource(Res.string.control_status_off)
    }
    HbRow(modifier.testTag("engine-status:$engineId"), gap = HbTheme.spacing.xxs) {
        if (status == EngineConnectionStatusUi.Connected) {
            HbIcon(
                HbIcons.Check,
                contentDescription = null,
                modifier = Modifier.testTag("engine-status-check:$engineId"),
                tint = HbTheme.surfaces.success,
            )
        }
        HbText(
            text,
            style = HbTheme.typography.caption,
            color = if (status == EngineConnectionStatusUi.Connected) {
                HbTheme.surfaces.success
            } else {
                HbTheme.colors.textSecondary
            },
            maxLines = 1,
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
