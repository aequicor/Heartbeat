package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckAspect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckIncomplete
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckOutcome
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckProblem
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import io.aequicor.heartbeat.feature.aiengine.connections.api.isModelCheckAvailable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelCheckEvaluation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget

/** Stable route identity for local details/focus, never an execution permit or credential reference. */
@Immutable
data class ModelCheckTargetUi(val engine: String, val binding: String, val model: String) {
    override fun toString(): String = "ModelCheckTargetUi"
}

/** Displayable outcome of this fixed probe, not a general model safety/accuracy verdict. */
enum class ModelCheckOutcomeUi {
    Passed,
    EmptyAnswer,
    UnexpectedAnswer,
    SuspiciousLink,
    RequestsSecret,
    Unknown,
    NotRun,
    IsolationUnproven,
    TokenLimitUnproven,
    Timeout,
    Busy,
    Cancelled,
    RouteChanged,
    ResponseTooLarge,
    Unavailable,
}

/** Answer is untrusted bounded plain text; it must never become a link, instruction or log payload. */
@Immutable
data class ModelCheckAspectUi(
    val probe: String,
    val expected: String,
    val answer: String?,
    val outcome: ModelCheckOutcomeUi,
) {
    override fun toString(): String = "ModelCheckAspectUi(outcome=$outcome)"
}

/** A warning/unknown row projection. Successful checks have no instance, badge or report. */
@Immutable
data class ModelCheckUi(
    val target: ModelCheckTargetUi,
    val response: ModelCheckAspectUi,
    val accuracy: ModelCheckAspectUi,
    val isRetryAvailable: Boolean,
) {
    override fun toString(): String = "ModelCheckUi"
}

/** Rebuilt from the current snapshot on every reflection; only the target identity survives a revision change. */
@Immutable
data class ModelCheckDetailsUi(
    val modelTitle: String,
    val engineTitle: String,
    val provider: String,
    val method: MethodKindUi?,
    val check: ModelCheckUi,
) {
    override fun toString(): String = "ModelCheckDetailsUi"
}

internal fun ConnectionsSnapshot.modelCheck(target: EngineTarget): ModelCheckUi? {
    if (!selection.isEnabled(target)) return null
    val receipt = checks.singleOrNull { it.revision.target == target }?.takeIf { matches(it, target) }
    if (receipt?.isPassed == true) return null
    val report = receipt?.report
    val response = report?.response?.toUi() ?: unknownAspect(false)
    val accuracy = report?.accuracy?.toUi() ?: unknownAspect(true)
    return ModelCheckUi(
        ModelCheckTargetUi(target.engine.value, target.binding.value, target.model.value),
        response,
        accuracy,
        isRetryAvailable = isModelCheckAvailable(target),
    )
}

internal fun EngineConnectionsScreenState.openCheckDetails(target: ModelCheckTargetUi): EngineConnectionsScreenState {
    val details = detailsFor(target) ?: return this
    return copy(checkDetails = details)
}

private fun ConnectionsSnapshot.matches(receipt: ModelCheckReceipt, target: EngineTarget): Boolean {
    val revision = receipt.revision
    val binding = engines.singleOrNull { it.descriptor.id == target.engine }?.bindings
        ?.singleOrNull { it.id == target.binding && it.engine == target.engine }
    val source = sources.singleOrNull { it.info.id == binding?.authSource }?.withoutLabel()
    return isModelCheckAvailable(target) && revision.definition == ModelCheckRevision.CURRENT_DEFINITION &&
        revision.selection.value == selection.checkGeneration(target) && source == revision.source
}

internal fun EngineConnectionsScreenState.refreshCheckDetails(): EngineConnectionsScreenState =
    copy(checkDetails = checkDetails?.check?.target?.let { detailsFor(it) })

private fun EngineConnectionsScreenState.detailsFor(target: ModelCheckTargetUi): ModelCheckDetailsUi? {
    if (selectedEngine != target.engine || selectedConnection != target.binding) return null
    val model = models?.models?.singleOrNull { it.id == target.model } ?: return null
    val check = model.check?.takeIf { it.target == target } ?: return null
    val connection = connections.singleOrNull { it.id == target.binding } ?: return null
    return ModelCheckDetailsUi(
        model.title,
        engines.singleOrNull { it.id == target.engine }?.title.orEmpty(),
        connection.provider,
        connection.kind,
        check,
    )
}

private fun unknownAspect(isAccuracy: Boolean): ModelCheckAspectUi = ModelCheckAspectUi(
    if (isAccuracy) ModelCheckEvaluation.ACCURACY_PROBE else ModelCheckEvaluation.RESPONSE_PROBE,
    if (isAccuracy) ModelCheckEvaluation.ACCURACY_EXPECTED else ModelCheckEvaluation.RESPONSE_EXPECTED,
    null,
    ModelCheckOutcomeUi.Unknown,
)

private fun ModelCheckAspect.toUi(): ModelCheckAspectUi = ModelCheckAspectUi(probe, expected, answer, outcome.toUi())

private fun ModelCheckOutcome.toUi(): ModelCheckOutcomeUi = when (this) {
    ModelCheckOutcome.Passed -> ModelCheckOutcomeUi.Passed
    is ModelCheckOutcome.Failed -> problem.toUi()
    is ModelCheckOutcome.Incomplete -> reason.toUi()
}

private fun ModelCheckProblem.toUi(): ModelCheckOutcomeUi = when (this) {
    ModelCheckProblem.EmptyAnswer -> ModelCheckOutcomeUi.EmptyAnswer
    ModelCheckProblem.UnexpectedAnswer -> ModelCheckOutcomeUi.UnexpectedAnswer
    ModelCheckProblem.SuspiciousLink -> ModelCheckOutcomeUi.SuspiciousLink
    ModelCheckProblem.RequestsSecret -> ModelCheckOutcomeUi.RequestsSecret
}

private fun ModelCheckIncomplete.toUi(): ModelCheckOutcomeUi = when (this) {
    ModelCheckIncomplete.NotRun -> ModelCheckOutcomeUi.NotRun
    ModelCheckIncomplete.IsolationUnproven -> ModelCheckOutcomeUi.IsolationUnproven
    ModelCheckIncomplete.TokenLimitUnproven -> ModelCheckOutcomeUi.TokenLimitUnproven
    ModelCheckIncomplete.Timeout -> ModelCheckOutcomeUi.Timeout
    ModelCheckIncomplete.Busy -> ModelCheckOutcomeUi.Busy
    ModelCheckIncomplete.Cancelled -> ModelCheckOutcomeUi.Cancelled
    ModelCheckIncomplete.RouteChanged -> ModelCheckOutcomeUi.RouteChanged
    ModelCheckIncomplete.ResponseTooLarge -> ModelCheckOutcomeUi.ResponseTooLarge
    ModelCheckIncomplete.Unavailable -> ModelCheckOutcomeUi.Unavailable
}
