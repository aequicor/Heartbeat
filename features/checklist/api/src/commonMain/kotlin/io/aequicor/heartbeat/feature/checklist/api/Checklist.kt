package io.aequicor.heartbeat.feature.checklist.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Action selected by the creator; answers are always supplied by the user. */
@Serializable
public enum class ChecklistCompletionMode { ResumeSession, MarkSessionReady }

/** A completed or superseded card stays in the transcript, without editable answers. */
@Serializable
public enum class ChecklistStatus { Open, Completed, Superseded }

/** Delivery is independent of the frozen answers. Unknown acceptance requires an explicit retry. */
@Serializable
public enum class ChecklistDelivery { None, Pending, Delivered, Failed }

/** Stable choice identity within a field. */
@Serializable
public data class ChecklistChoice(val id: String, val title: String)

/** Input type, independent of the questionnaire's transient permission lifecycle. */
@Serializable
public enum class ChecklistInput { SingleChoice, MultiChoice, Text }

/** One field; optional fields may be left empty, but any supplied answer must fit its type and bounds. */
@Serializable
public data class ChecklistField(
    val id: String,
    val title: String,
    val type: ChecklistInput,
    @SerialName("required") val isRequired: Boolean = true,
    val choices: List<ChecklistChoice> = emptyList(),
    val min: Int = if (isRequired) 1 else 0,
    val max: Int = choices.size,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(choices.all { it.id.isNotBlank() && it.title.isNotBlank() })
        require(choices.map { it.id }.distinct().size == choices.size)
        require(type == ChecklistInput.Text || choices.isNotEmpty())
        require(type != ChecklistInput.MultiChoice || (min in 0..max && max <= choices.size))
    }
}

/** Draft or frozen answer. No model-facing tool can change these values. */
@Serializable
public data class ChecklistAnswer(val selected: Set<String> = emptySet(), val text: String = "")

/** Durable message attachment; owner identities come from the adapter, never from model arguments. */
@Serializable
public data class Checklist(
    val id: String,
    val session: SessionRef,
    val request: RequestId,
    val turn: TurnId,
    val callId: String?,
    val workspace: WorkspaceRef?,
    val target: EngineTarget?,
    val title: String,
    val fields: List<ChecklistField>,
    val mode: ChecklistCompletionMode = ChecklistCompletionMode.ResumeSession,
    val answers: Map<String, ChecklistAnswer> = emptyMap(),
    val status: ChecklistStatus = ChecklistStatus.Open,
    val delivery: ChecklistDelivery = ChecklistDelivery.None,
    val revision: Long = 1,
    val attempt: Int = 0,
    val creationKey: String = id,
    val historyTurn: TurnId = turn,
) {
    init {
        require(Regex("[a-z0-9_-]{1,32}").matches(id))
        require(title.isNotBlank() && fields.isNotEmpty())
        require(fields.map { it.id }.distinct().size == fields.size)
    }

    /** A button is unnecessary only for an exclusively single-choice card. */
    public val isAutomatic: Boolean get() = fields.all { it.type == ChecklistInput.SingleChoice }

    /** Whether the current answers satisfy every field. */
    public val isCompletionAllowed: Boolean get() = status == ChecklistStatus.Open && fields.all { field ->
        val answer = answers[field.id] ?: ChecklistAnswer()
        field.accepts(answer)
    }

    override fun toString(): String = "Checklist(id=$id, status=$status, revision=$revision)"
}

/** Validates a completed answer; empty optional fields are allowed. */
public fun ChecklistField.accepts(answer: ChecklistAnswer): Boolean {
    if (!acceptsDraft(answer)) return false
    if (!isRequired && answer.text.isBlank() && answer.selected.isEmpty()) return true
    return when (type) {
        ChecklistInput.Text -> !isRequired || answer.text.isNotBlank()
        ChecklistInput.SingleChoice -> answer.selected.size == 1
        ChecklistInput.MultiChoice -> answer.selected.size in min..max
    }
}

/** Drafts can be incomplete but cannot contain unknown choices, mixed answer types or excess selections. */
public fun ChecklistField.acceptsDraft(answer: ChecklistAnswer): Boolean = when (type) {
    ChecklistInput.Text -> answer.selected.isEmpty()
    ChecklistInput.SingleChoice -> answer.text.isEmpty() && answer.selected.size <= 1 && offered(answer)
    ChecklistInput.MultiChoice -> answer.text.isEmpty() && answer.selected.size <= max && offered(answer)
}

private fun ChecklistField.offered(answer: ChecklistAnswer): Boolean =
    answer.selected.all { selected -> choices.any { it.id == selected } }
