package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.feedback.api.FeedbackChange
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord

internal fun FeedbackRecord.toUi(): FeedbackUi {
    val actual = (outcome as? FeedbackOutcome.Applied)?.configuration
    val value = when (val changed = change) {
        is FeedbackChange.Model -> FeedbackUi(
            FeedbackParameterUi.Model,
            outcome.toUi(),
            value = actual?.model?.value ?: changed.requested.model.value,
        )

        is FeedbackChange.Effort -> FeedbackUi(
            FeedbackParameterUi.Effort,
            outcome.toUi(),
            value = if (actual != null) actual.reasoningEffort else changed.requested,
        )

        is FeedbackChange.Trust -> FeedbackUi(
            FeedbackParameterUi.Trust,
            outcome.toUi(),
            approval = (if (actual != null) actual.trust else changed.requested)?.toUi(),
        )
    }
    val failed = outcome as? FeedbackOutcome.Failed ?: return value
    val model = change as? FeedbackChange.Model
    val isConnectionChanged = model != null &&
        (model.before.engine != model.requested.engine || model.before.binding != model.requested.binding)
    return value.copy(failure = if (isConnectionChanged) FeedbackFailureUi.ConnectionChange else failed.failure.toUi())
}

private fun FeedbackOutcome.toUi(): FeedbackOutcomeUi = when (this) {
    FeedbackOutcome.Pending -> FeedbackOutcomeUi.Pending
    is FeedbackOutcome.Applied -> FeedbackOutcomeUi.Applied
    is FeedbackOutcome.Failed -> FeedbackOutcomeUi.Failed
    FeedbackOutcome.Unknown -> FeedbackOutcomeUi.Unknown
}

private fun TrustLevel.toUi(): ApprovalUi = when (this) {
    TrustLevel.Ask -> ApprovalUi.Ask
    TrustLevel.AutoEdits -> ApprovalUi.AutoEdits
    TrustLevel.Full -> ApprovalUi.AutoApprove
}

private fun EngineFailure.toUi(): FeedbackFailureUi = when (this) {
    is EngineFailure.Engine -> if (reason == EngineFailureReason.UnsupportedCapability) {
        FeedbackFailureUi.Unsupported
    } else {
        FeedbackFailureUi.Unknown
    }

    is EngineFailure.Session -> if (reason == SessionFailureReason.Busy) {
        FeedbackFailureUi.Busy
    } else {
        FeedbackFailureUi.Unknown
    }

    is EngineFailure.Request -> when (reason) {
        RequestFailureReason.Invalid -> FeedbackFailureUi.Invalid
        RequestFailureReason.UnsupportedContent -> FeedbackFailureUi.Unsupported
        RequestFailureReason.OutcomeUnknown -> FeedbackFailureUi.Unknown
    }

    is EngineFailure.Access, is EngineFailure.Authentication -> FeedbackFailureUi.Access

    is EngineFailure.Transport -> FeedbackFailureUi.Transport

    is EngineFailure.Lifecycle -> FeedbackFailureUi.Lifecycle

    is EngineFailure.RateLimited, is EngineFailure.QuotaExceeded, is EngineFailure.ContextLimitExceeded,
    is EngineFailure.History, is EngineFailure.Unknown,
    -> FeedbackFailureUi.Unknown
}
