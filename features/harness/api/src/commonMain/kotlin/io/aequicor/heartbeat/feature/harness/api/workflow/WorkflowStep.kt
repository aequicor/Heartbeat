package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Structural path: zero or more parallel branch indices followed by the sequential step index. */
@Serializable
public data class StepKey(val value: String) {
    init {
        require(value.length <= MAX_STEP_KEY_LENGTH && Regex("(?:p[0-9]+/)*s[0-9]+").matches(value))
    }
}

/** Journal boundaries; Prepared identity is saved before prompting and terminal values are immutable. */
@Serializable
public enum class StepPhase { Prepared, Running, Completed, Failed }

/** One replayable step; text, arguments and results never appear in diagnostic strings. */
@Serializable
public data class WorkflowStep(
    val key: StepKey,
    val promptSha: String,
    val helper: HelperId? = null,
    val session: SessionRef? = null,
    val request: RequestId? = null,
    val attempt: Int = 0,
    val turn: TurnId? = null,
    val phase: StepPhase = StepPhase.Prepared,
    @Serializable(with = WorkflowStepResultSerializer::class) val result: JsonElement? = null,
    val failure: WorkflowFailure? = null,
) {
    init {
        require(isWorkflowDigest(promptSha) && attempt >= 0)
        require(result == null || result.toString().length <= HarnessLimits.RESULT_CHARS)
        require((phase == StepPhase.Completed) == (result != null))
        require((phase == StepPhase.Failed) == (failure != null))
        require(turn == null || (session != null && request != null))
        require(helper == null || request != null)
    }

    override fun toString(): String = "WorkflowStep(key=$key, attempt=$attempt, phase=$phase, ***)"
}

private const val MAX_STEP_KEY_LENGTH = 256
