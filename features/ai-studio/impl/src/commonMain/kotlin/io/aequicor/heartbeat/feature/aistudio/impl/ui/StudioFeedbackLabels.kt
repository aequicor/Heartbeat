package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FeedbackFailureUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FeedbackOutcomeUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FeedbackParameterUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FeedbackUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_ask
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_auto
import io.aequicor.heartbeat.feature.aistudio.impl.resources.approval_edits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_applied_request
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_applied_tool
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_default
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_effort
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failure_access
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failure_busy
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failure_connection
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failure_invalid
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failure_lifecycle
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failure_transport
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failure_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_failure_unsupported
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_kept
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_model
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_next_request
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_next_tool
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_pending
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_pending_detail
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_trust
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.feedback_unknown_detail
import org.jetbrains.compose.resources.stringResource

/** Localized feedback copy prepared in composition and formatted with confirmed UI values. */
@Immutable
internal data class FeedbackLabels(
    val model: String,
    val effort: String,
    val trust: String,
    val defaultValue: String,
    val approvals: FeedbackApprovalLabels,
    val outcomes: FeedbackOutcomeLabels,
    val failures: FeedbackFailureLabels,
) {
    /** Collapsed tool text never depends on provider error messages. */
    fun summary(value: FeedbackUi): String {
        val parameter = when (value.parameter) {
            FeedbackParameterUi.Model -> model
            FeedbackParameterUi.Effort -> effort
            FeedbackParameterUi.Trust -> trust
        }
        val actual = if (value.parameter == FeedbackParameterUi.Trust) {
            approvals.of(value.approval) ?: defaultValue
        } else {
            value.value ?: defaultValue
        }
        return when (value.outcome) {
            FeedbackOutcomeUi.Pending -> fill(outcomes.pending, parameter, actual)

            FeedbackOutcomeUi.Applied -> fill(
                if (value.parameter == FeedbackParameterUi.Trust) outcomes.appliedTool else outcomes.appliedRequest,
                parameter,
                actual,
            )

            FeedbackOutcomeUi.Failed -> fill(outcomes.failed, parameter, failures.of(value.failure))

            FeedbackOutcomeUi.Unknown -> fill(outcomes.unknown, parameter)
        }
    }

    /** Expanded text preserves literal parameter values and states the acknowledgement boundary. */
    fun markdown(value: FeedbackUi): String {
        val detail = when (value.outcome) {
            FeedbackOutcomeUi.Pending -> outcomes.pendingDetail

            FeedbackOutcomeUi.Applied -> if (value.parameter == FeedbackParameterUi.Trust) {
                outcomes.nextTool
            } else {
                outcomes.nextRequest
            }

            FeedbackOutcomeUi.Failed -> outcomes.kept

            FeedbackOutcomeUi.Unknown -> outcomes.unknownDetail
        }
        return summary(value).escapeMarkdown() + "\n\n" + detail
    }
}

/** Composer approval names are reused in feedback rather than exposing native trust enums. */
@Immutable
internal data class FeedbackApprovalLabels(val ask: String, val edits: String, val auto: String) {
    fun of(value: ApprovalUi?): String? = when (value) {
        ApprovalUi.Ask -> ask
        ApprovalUi.AutoEdits -> edits
        ApprovalUi.AutoApprove -> auto
        null -> null
    }
}

/** Templates and explanation paragraphs for every persisted outcome. */
@Immutable
internal data class FeedbackOutcomeLabels(
    val pending: String,
    val appliedTool: String,
    val appliedRequest: String,
    val failed: String,
    val unknown: String,
    val pendingDetail: String,
    val nextTool: String,
    val nextRequest: String,
    val kept: String,
    val unknownDetail: String,
)

/** Safe explanations of rejected configuration operations. */
@Immutable
internal data class FeedbackFailureLabels(
    val unsupported: String,
    val busy: String,
    val connection: String,
    val invalid: String,
    val access: String,
    val transport: String,
    val lifecycle: String,
    val unknown: String,
) {
    fun of(value: FeedbackFailureUi?): String = when (value) {
        FeedbackFailureUi.Unsupported -> unsupported
        FeedbackFailureUi.Busy -> busy
        FeedbackFailureUi.ConnectionChange -> connection
        FeedbackFailureUi.Invalid -> invalid
        FeedbackFailureUi.Access -> access
        FeedbackFailureUi.Transport -> transport
        FeedbackFailureUi.Lifecycle -> lifecycle
        FeedbackFailureUi.Unknown, null -> unknown
    }
}

private fun String.escapeMarkdown(): String = buildString {
    for (char in this@escapeMarkdown) {
        if (char in "\\*_`[]<>") append('\\')
        append(char)
    }
}

/** Feedback tool copy in the current application language. */
@Composable
internal fun studioFeedbackLabels(): FeedbackLabels = FeedbackLabels(
    model = stringResource(Res.string.feedback_model),
    effort = stringResource(Res.string.feedback_effort),
    trust = stringResource(Res.string.feedback_trust),
    defaultValue = stringResource(Res.string.feedback_default),
    approvals = FeedbackApprovalLabels(
        ask = stringResource(Res.string.approval_ask),
        edits = stringResource(Res.string.approval_edits),
        auto = stringResource(Res.string.approval_auto),
    ),
    outcomes = FeedbackOutcomeLabels(
        pending = stringResource(Res.string.feedback_pending),
        appliedTool = stringResource(Res.string.feedback_applied_tool),
        appliedRequest = stringResource(Res.string.feedback_applied_request),
        failed = stringResource(Res.string.feedback_failed),
        unknown = stringResource(Res.string.feedback_unknown),
        pendingDetail = stringResource(Res.string.feedback_pending_detail),
        nextTool = stringResource(Res.string.feedback_next_tool),
        nextRequest = stringResource(Res.string.feedback_next_request),
        kept = stringResource(Res.string.feedback_kept),
        unknownDetail = stringResource(Res.string.feedback_unknown_detail),
    ),
    failures = FeedbackFailureLabels(
        unsupported = stringResource(Res.string.feedback_failure_unsupported),
        busy = stringResource(Res.string.feedback_failure_busy),
        connection = stringResource(Res.string.feedback_failure_connection),
        invalid = stringResource(Res.string.feedback_failure_invalid),
        access = stringResource(Res.string.feedback_failure_access),
        transport = stringResource(Res.string.feedback_failure_transport),
        lifecycle = stringResource(Res.string.feedback_failure_lifecycle),
        unknown = stringResource(Res.string.feedback_failure_unknown),
    ),
)
