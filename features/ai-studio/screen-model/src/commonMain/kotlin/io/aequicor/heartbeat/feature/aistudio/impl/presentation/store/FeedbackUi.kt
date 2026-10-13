package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Session setting named by a localized feedback tool card. */
@Serializable
@SerialName("FeedbackParameterUi")
enum class FeedbackParameterUi { Model, Effort, Trust }

/** Whether a configuration operation has been acknowledged. */
@Serializable
@SerialName("FeedbackOutcomeUi")
enum class FeedbackOutcomeUi { Pending, Applied, Failed, Unknown }

/** Safe failure classes localized by the timeline, without exposing native errors. */
@Serializable
@SerialName("FeedbackFailureUi")
enum class FeedbackFailureUi { Unsupported, Busy, ConnectionChange, Invalid, Access, Transport, Lifecycle, Unknown }

/** UI-only feedback values; successful cards show confirmed parameters and other cards show the request. */
@Serializable
@SerialName("FeedbackUi")
@Immutable
data class FeedbackUi(
    val parameter: FeedbackParameterUi,
    val outcome: FeedbackOutcomeUi,
    val value: String? = null,
    val approval: ApprovalUi? = null,
    val failure: FeedbackFailureUi? = null,
)
