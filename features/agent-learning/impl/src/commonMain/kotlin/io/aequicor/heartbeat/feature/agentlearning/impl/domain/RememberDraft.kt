package io.aequicor.heartbeat.feature.agentlearning.impl.domain

import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearningLimits
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools.Arguments
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How far a model instruction reaches: the whole current engine or only its current model. */
internal enum class ModelReach { Engine, Model }

/**
 * A validated `remember` call; scope and identity are added by the host, never taken from the model. [title] and
 * [description] are single lines; [reason] is shown to the user only, folded into one line.
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

/** Reads and validates `remember` arguments; texts are trimmed and checked against [LearningLimits]. */
internal fun parseRemember(arguments: JsonObject): DraftResult {
    val kind = kindOf(arguments.text(Arguments.KIND))
        ?: return DraftResult.Invalid("${Arguments.KIND} must be general, model or skill")
    val reach = when (val value = arguments.text(Arguments.MODEL_SCOPE)) {
        null -> ModelReach.Model
        else -> reachOf(value) ?: return DraftResult.Invalid("${Arguments.MODEL_SCOPE} must be engine or model")
    }
    val draft = RememberDraft(
        kind,
        title = arguments.text(Arguments.TITLE).orEmpty(),
        content = arguments.text(Arguments.CONTENT).orEmpty(),
        description = arguments.text(Arguments.DESCRIPTION).orEmpty(),
        reach = reach.takeIf { kind == InstructionKind.Model },
        isSafe = isRatedSafe(arguments),
        reason = arguments.text(Arguments.REASON).orEmpty().truncated(LearningLimits.DESCRIPTION),
    )
    val safety = arguments.text(Arguments.SAFETY)
    val problem = if (safety != "safe" && safety != "review") {
        "${Arguments.SAFETY} must be safe or review"
    } else {
        draft.problem()
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

    // A line break would let the title or description pose as other text in the approval or the prompt.
    title.hasLineBreak() -> "title must be a single line"

    description.hasLineBreak() -> "description must be a single line; put details into content"

    hasHiddenCharacters(title + description + content + reason) ->
        "it contains invisible or control characters; use plain text"

    looksLikeSecret("$title\n$description\n$content") -> "it looks like a credential; secrets are never stored"

    else -> null
}

/** Whether the agent rated the instruction in [arguments] safe; anything unreadable counts as unsafe. */
internal fun isRatedSafe(arguments: JsonObject): Boolean = arguments.text(Arguments.SAFETY) == "safe"

/** [text] on one line: every run of whitespace, line breaks included, becomes a single space. */
internal fun singleLine(text: String): String = buildString {
    var isSpace = false
    for (char in text.trim()) {
        if (char.isWhitespace()) {
            if (!isSpace) append(' ')
        } else {
            append(char)
        }
        isSpace = char.isWhitespace()
    }
}

private fun String.hasLineBreak(): Boolean = any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }

/** At most [limit] characters, never ending in half of a surrogate pair. */
private fun String.truncated(limit: Int): String = when {
    length <= limit -> this
    this[limit - 1].isHighSurrogate() -> take(limit - 1)
    else -> take(limit)
}

/**
 * Whether [text] carries characters a reviewer cannot see: control characters other than line breaks and tabs,
 * format characters (bidi overrides, zero-width marks) and Unicode tag characters (U+E0000..U+E007F).
 */
internal fun hasHiddenCharacters(text: String): Boolean = text.indices.any { index ->
    val char = text[index]
    val isControl = char.isISOControl() && char != '\n' && char != '\t' && char != '\r'
    isControl || char.category == CharCategory.FORMAT || text.isTagCharacterAt(index)
}

private fun String.isTagCharacterAt(index: Int): Boolean {
    if (!this[index].isHighSurrogate() || index + 1 >= length) return false
    val code = SUPPLEMENTARY_BASE + ((this[index].code - HIGH_SURROGATE_BASE) shl SURROGATE_BITS) +
        (this[index + 1].code - LOW_SURROGATE_BASE)
    return code in TAG_CHARACTERS
}

private const val SUPPLEMENTARY_BASE = 0x10000
private const val HIGH_SURROGATE_BASE = 0xD800
private const val LOW_SURROGATE_BASE = 0xDC00
private const val SURROGATE_BITS = 10
private val TAG_CHARACTERS = 0xE0000..0xE007F

/** Common credential shapes: provider keys, tokens, private keys and `key = value` assignments. */
internal fun looksLikeSecret(text: String): Boolean = SECRET_PATTERNS.any { it.containsMatchIn(text) }

private val SECRET_PATTERNS = listOf(
    Regex("""\bsk-[A-Za-z0-9_-]{20,}"""),
    Regex("""\b(?:ghp|gho|ghu|ghs|github_pat)_[A-Za-z0-9_]{20,}"""),
    Regex("""\bAKIA[0-9A-Z]{16}\b"""),
    Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""),
    Regex("""\bxox[abposr]-[A-Za-z0-9-]{10,}"""),
    Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
    Regex("""(?i)\b(?:api[_-]?key|access[_-]?token|secret|password|passwd)\b\s*[:=]\s*["']?[^\s"']{8,}"""),
)

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
