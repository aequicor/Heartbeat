package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.AvailabilityUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.MethodKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_availability_available
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_availability_unavailable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_availability_unknown
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_availability_unsupported
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

/** A list entry that can be selected; selection is exposed to accessibility as a single choice. */
@Composable
internal fun SelectableRow(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = HbTheme.shapes.medium
    HbPanel(
        modifier.fillMaxWidth().heightIn(min = HbTheme.dimensions.touchTarget).clip(shape)
            .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        background = if (isSelected) HbTheme.colors.accentMuted else HbTheme.colors.glassTint,
        shape = shape,
    ) {
        HbColumn(Modifier.padding(HbTheme.spacing.l), gap = HbTheme.spacing.xs, content = content)
    }
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

/** Failure panel announced politely to screen readers, with optional recovery actions. */
@Composable
internal fun FailurePanel(
    failure: FailureUi,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    HbPanel(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        background = HbTheme.colors.errorContainer,
    ) {
        HbColumn(Modifier.padding(HbTheme.spacing.l), gap = HbTheme.spacing.s) {
            HbText(stringResource(failure.message()))
            HbFlowRow {
                if (onRetry != null) {
                    HbButton(stringResource(Res.string.conn_retry), onRetry, style = HbButtonStyle.Secondary)
                }
                extra()
            }
        }
    }
}
