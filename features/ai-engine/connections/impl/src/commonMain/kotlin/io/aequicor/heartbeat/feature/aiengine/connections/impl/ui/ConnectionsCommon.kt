package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.AvailabilityUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.MethodKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_availability_available
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_availability_unavailable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_availability_unknown
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_availability_unsupported
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_back
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_failure_access
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_failure_authentication
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_failure_engine
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_failure_limit
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_failure_network
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_failure_rejected
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_failure_unknown
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_kind_api_key
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_kind_cli
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_kind_no_auth
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_retry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A settings row that is one option of a single-choice list: selection is shown by the quiet selected fill and
 * exposed to accessibility. [description] is the secondary 12sp line.
 */
@Composable
internal fun SelectableRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    HbSettingsRow(
        title,
        modifier.semantics { selected = isSelected },
        description = description,
        onClick = onClick,
        enabled = enabled,
        isSelected = isSelected,
        trailingContent = trailingContent,
    )
}

/** Header of a screen opened outside the settings host: back, title and the traffic-light inset. */
@Composable
internal fun StandaloneHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    HbPaneHeader(
        title,
        modifier,
        leadingInset = if (HbTheme.dimensions.isDesktop) {
            HbTheme.spacing.m
        } else {
            HbTheme.dimensions.titlebarLeadingInset
        },
        navigation = {
            HbIconButton(
                HbIcons.ArrowLeft,
                stringResource(Res.string.conn_back),
                onBack,
                Modifier.testTag("settings-back"),
            )
        },
    )
}

/** Installation badge of an engine. */
@Composable
internal fun AvailabilityBadge(availability: AvailabilityUi, modifier: Modifier = Modifier) {
    val (text, tone) = when (availability) {
        AvailabilityUi.Unknown -> Res.string.conn_availability_unknown to HbTone.Neutral
        AvailabilityUi.Available -> Res.string.conn_availability_available to HbTone.Success
        AvailabilityUi.Unsupported -> Res.string.conn_availability_unsupported to HbTone.Neutral
        AvailabilityUi.Unavailable -> Res.string.conn_availability_unavailable to HbTone.Warning
    }
    HbBadge(stringResource(text), modifier, tone)
}

/** Localized name of an authentication method kind. */
@Composable
internal fun methodKindLabel(kind: MethodKindUi): String = stringResource(
    when (kind) {
        MethodKindUi.ApiKey -> Res.string.conn_kind_api_key
        MethodKindUi.CliLogin -> Res.string.conn_kind_cli
        MethodKindUi.NoAuth -> Res.string.conn_kind_no_auth
    },
)

/** Localized, diagnostics-free explanation of a failure. */
internal fun FailureUi.message(): StringResource = when (this) {
    FailureUi.Authentication -> Res.string.conn_failure_authentication
    FailureUi.Network -> Res.string.conn_failure_network
    FailureUi.Limit -> Res.string.conn_failure_limit
    FailureUi.EngineUnavailable -> Res.string.conn_failure_engine
    FailureUi.Access -> Res.string.conn_failure_access
    FailureUi.Rejected -> Res.string.conn_failure_rejected
    FailureUi.Unknown -> Res.string.conn_failure_unknown
}

/** Failure banner announced politely to screen readers, with optional recovery actions. */
@Composable
internal fun FailurePanel(
    failure: FailureUi,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    HbBanner(stringResource(failure.message()), modifier) {
        extra()
        if (onRetry != null) {
            HbButton(
                stringResource(Res.string.conn_retry),
                onRetry,
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
    }
}
