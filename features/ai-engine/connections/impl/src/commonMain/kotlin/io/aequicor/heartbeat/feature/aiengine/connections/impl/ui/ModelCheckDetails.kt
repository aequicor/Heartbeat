package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelCheckAspectUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelCheckDetailsUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelCheckOutcomeUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelCheckUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_accuracy
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_answer
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_cancelled
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_changed
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_close
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_empty
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_expected
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_isolation
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_link
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_no_answer
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_open
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_passed
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_probe
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_response
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_retry
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_scope_notice
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_secret
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_secret_notice
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_timeout
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_title
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_tokens
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_too_large
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_unavailable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_unexpected
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.check_unknown
import org.jetbrains.compose.resources.stringResource

/** Current diagnostics are plain text. Opening and closing this view never submits a model request. */
@Composable
internal fun ModelCheckDetailsDialog(
    details: ModelCheckDetailsUi,
    isBusy: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbDialog(
        stringResource(Res.string.check_title),
        onDismiss,
        modifier.testTag("model-check-details"),
        actions = {
            HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
                HbButton(
                    stringResource(Res.string.check_retry),
                    onRetry,
                    Modifier.testTag("model-check-retry"),
                    style = HbButtonStyle.Secondary,
                    enabled = !isBusy && details.check.isRetryAvailable,
                )
                Spacer(Modifier.weight(1f))
                HbButton(
                    stringResource(Res.string.check_close),
                    onDismiss,
                    Modifier.testTag("model-check-close"),
                    style = HbButtonStyle.Secondary,
                )
            }
        },
    ) {
        HbColumn(gap = HbTheme.spacing.xl) {
            HbText(
                listOfNotNull(
                    details.modelTitle,
                    details.engineTitle,
                    details.provider,
                    details.method?.let { methodKindLabel(it) },
                ).filter { it.isNotBlank() }.joinToString(" · "),
                color = HbTheme.colors.textSecondary,
                style = HbTheme.typography.caption,
            )
            CheckAspect(stringResource(Res.string.check_response), details.check.response)
            CheckAspect(stringResource(Res.string.check_accuracy), details.check.accuracy)
            HbText(stringResource(Res.string.check_secret_notice))
            HbText(
                stringResource(Res.string.check_scope_notice),
                color = HbTheme.colors.textSecondary,
                style = HbTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun CheckAspect(title: String, aspect: ModelCheckAspectUi) {
    HbColumn(gap = HbTheme.spacing.s) {
        HbText("$title: ${checkOutcomeLabel(aspect.outcome)}", style = HbTheme.typography.label)
        HbText(stringResource(Res.string.check_probe, aspect.probe))
        HbText(stringResource(Res.string.check_expected, aspect.expected))
        HbText(stringResource(Res.string.check_answer, aspect.answer ?: stringResource(Res.string.check_no_answer)))
    }
}

@Composable
internal fun ModelCheckInfo(
    model: ModelRowUi,
    isOpen: Boolean,
    onOpen: (ModelCheckUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val check = model.check
    val focus = remember(model.id, check?.target) { FocusRequester() }
    var isPreviouslyOpen by remember(model.id, check?.target) { mutableStateOf(false) }
    LaunchedEffect(isOpen, check?.target, focus) {
        if (isPreviouslyOpen && !isOpen && check != null) {
            withFrameNanos { }
            focus.requestFocus()
        }
        isPreviouslyOpen = isOpen
    }
    if (check != null) {
        HbIconButton(
            HbIcons.Info,
            stringResource(Res.string.check_open, model.title),
            { onOpen(check) },
            modifier.focusRequester(focus).testTag("model-check-info:${model.id}"),
        )
    }
}

@Composable
private fun checkOutcomeLabel(outcome: ModelCheckOutcomeUi): String = stringResource(
    when (outcome) {
        ModelCheckOutcomeUi.Passed -> Res.string.check_passed
        ModelCheckOutcomeUi.EmptyAnswer -> Res.string.check_empty
        ModelCheckOutcomeUi.UnexpectedAnswer -> Res.string.check_unexpected
        ModelCheckOutcomeUi.SuspiciousLink -> Res.string.check_link
        ModelCheckOutcomeUi.RequestsSecret -> Res.string.check_secret
        ModelCheckOutcomeUi.Unknown, ModelCheckOutcomeUi.NotRun -> Res.string.check_unknown
        ModelCheckOutcomeUi.IsolationUnproven -> Res.string.check_isolation
        ModelCheckOutcomeUi.TokenLimitUnproven -> Res.string.check_tokens
        ModelCheckOutcomeUi.Timeout -> Res.string.check_timeout
        ModelCheckOutcomeUi.Busy, ModelCheckOutcomeUi.Unavailable -> Res.string.check_unavailable
        ModelCheckOutcomeUi.Cancelled -> Res.string.check_cancelled
        ModelCheckOutcomeUi.RouteChanged -> Res.string.check_changed
        ModelCheckOutcomeUi.ResponseTooLarge -> Res.string.check_too_large
    },
)
