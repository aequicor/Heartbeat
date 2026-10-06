package io.aequicor.heartbeat.feature.agentlearning.impl.domain

import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearningLimits
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools.Arguments
import io.aequicor.heartbeat.feature.aiengine.facade.api.hasHiddenCharacters
import io.aequicor.heartbeat.feature.aiengine.facade.api.looksLikeSecret
import io.aequicor.heartbeat.feature.aiengine.facade.api.singleLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How far a model instruction reaches: the whole current engine or only its current model. */
internal enum class ModelReach { Engine, Model }

/**
 * A validated `remember` call; scope and identity are added by the host, never taken from the model. [title] and
 * [description] are single lines with every run of whitespace folded into one space; [content] has no two blank lines
 * in a row; [reason] is shown to the user only, folded into one line.
 */
internal data class RememberDraft(
    val kind: InstructionKind,
    val title: String,
    val content: String,
    val description: String,
    val reach: ModelReach?,
    val isSafe: Boolean,
    val reason: String,
) {
    override fun toString(): String = "RememberDraft(kind=$kind, safe=$isSafe)"
}

/** Outcome of reading `remember` arguments: a draft or the message returned to the model. */
internal sealed interface DraftResult {
    /** Arguments are complete and within limits. */
    data class Valid(val draft: RememberDraft) : DraftResult

    /** Arguments are unusable; [message] tells the model what to fix. */
    data class Invalid(val message: String) : DraftResult
}

/**
 * Reads and validates `remember` arguments; texts are trimmed, the title and description folded onto one line, and
 * checked against [LearningLimits].
 */
internal fun parseRemember(arguments: JsonObject): DraftResult {
    val kind = kindOf(arguments.text(Arguments.KIND))
        ?: return DraftResult.Invalid("${Arguments.KIND} must be general, model or skill")
    val reach = when (val value = arguments.text(Arguments.MODEL_SCOPE)) {
        null -> ModelReach.Model
        else -> reachOf(value) ?: return DraftResult.Invalid("${Arguments.MODEL_SCOPE} must be engine or model")
    }
    val title = arguments.text(Arguments.TITLE).orEmpty()
    val description = arguments.text(Arguments.DESCRIPTION).orEmpty()
    val draft = RememberDraft(
        kind,
        // A long run of spaces would wrap like a line break, so the stored text keeps a single space instead.
        title = singleLine(title),
        content = arguments.text(Arguments.CONTENT).orEmpty(),
        description = singleLine(description),
        reach = reach.takeIf { kind == InstructionKind.Model },
        isSafe = isRatedSafe(arguments),
        reason = arguments.text(Arguments.REASON).orEmpty().truncated(LearningLimits.DESCRIPTION),
    )
    val safety = arguments.text(Arguments.SAFETY)
    val problem = when {
        safety != "safe" && safety != "review" -> "${Arguments.SAFETY} must be safe or review"

        // A line break would let the title or description pose as other text in the approval or the prompt.
        title.hasLineBreak() -> "title must be a single line"

        description.hasLineBreak() -> "description must be a single line; put details into content"

        else -> draft.problem()
    }
    return problem?.let(DraftResult::Invalid) ?: DraftResult.Valid(draft)
}

private fun kindOf(value: String?): InstructionKind? = when (value) {
    "general" -> InstructionKind.General
    "model" -> InstructionKind.Model
    "skill" -> InstructionKind.Skill
    else -> null
}

private fun reachOf(value: String): ModelReach? = when (value) {
    "engine" -> ModelReach.Engine
    "model" -> ModelReach.Model
    else -> null
}

private fun RememberDraft.problem(): String? = when {
    title.isEmpty() -> "title is required"

    content.isEmpty() -> "content is required"

    kind == InstructionKind.Skill && description.isEmpty() ->
        "a skill needs a description of when to use it"

    title.length > LearningLimits.TITLE -> "title is longer than ${LearningLimits.TITLE} characters"

    description.length > LearningLimits.DESCRIPTION ->
        "description is longer than ${LearningLimits.DESCRIPTION} characters"

    content.length > LearningLimits.content(kind) ->
        "content is longer than ${LearningLimits.content(kind)} characters; make it shorter"

    // Blank lines could push the rest of the approval out of its window.
    content.hasBlankLines() ->
        "content has several blank lines in a row; use at most one blank line between paragraphs"

    hasHiddenCharacters(title + description + content + reason) ->
        "it contains invisible or control characters; use plain text"

    looksLikeSecret("$title\n$description\n$content") -> "it looks like a credential; secrets are never stored"

    else -> null
}

/** Whether the agent rated the instruction in [arguments] safe; anything unreadable counts as unsafe. */
internal fun isRatedSafe(arguments: JsonObject): Boolean = arguments.text(Arguments.SAFETY) == "safe"

private fun String.hasLineBreak(): Boolean = any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }

/** Whether two lines in a row are blank; a line of whitespace only counts as blank. */
private fun String.hasBlankLines(): Boolean = split(LINE_BREAK).zipWithNext().any { (line, next) ->
    line.isBlank() && next.isBlank()
}

private val LINE_BREAK = Regex("\r\n|[\n\r\u2028\u2029]")

/** At most [limit] characters, never ending in half of a surrogate pair. */
private fun String.truncated(limit: Int): String = when {
    length <= limit -> this
    this[limit - 1].isHighSurrogate() -> take(limit - 1)
    else -> take(limit)
}

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
