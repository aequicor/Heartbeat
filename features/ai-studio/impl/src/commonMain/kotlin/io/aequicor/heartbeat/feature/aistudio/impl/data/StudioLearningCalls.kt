package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostedToolName
import io.aequicor.heartbeat.feature.aistudio.impl.domain.LearningAction
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioLearningCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val log = Log.tag("StudioLearningCalls")

/**
 * The self-learning call behind a native tool call named [name], with its readable arguments, or null for any other
 * tool. Unreadable arguments still mark the call so the card does not dump raw JSON.
 */
internal fun learningCall(name: String, arguments: String): StudioLearningCall? {
    val action = when (hostedToolName(name)) {
        LearningTools.REMEMBER -> LearningAction.Remember
        LearningTools.LOAD_SKILL -> LearningAction.LoadSkill
        else -> return null
    }
    val values = parse(arguments)
    return StudioLearningCall(
        action,
        kind = values?.text(LearningTools.Arguments.KIND)?.let(::kindOf),
        title = values?.text(
            if (action == LearningAction.LoadSkill) LearningTools.Arguments.NAME else LearningTools.Arguments.TITLE,
        ).orEmpty(),
        content = values?.text(LearningTools.Arguments.CONTENT).orEmpty(),
    )
}

/** The kind named by its serial name in the tool arguments; an unknown one is shown without a kind. */
private fun kindOf(value: String): InstructionKind? = InstructionKind.entries.firstOrNull {
    InstructionKind.serializer().descriptor.getElementName(it.ordinal) == value
}

/** Streaming arguments may still be incomplete; such a card fills in with a later revision of the call. */
private fun parse(arguments: String): JsonObject? {
    val text = arguments.trim()
    if (!text.startsWith("{") || !text.endsWith("}")) return null
    return try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        log.w(e.withoutInput()) { "Learning call card falls back to its title" }
        null
    }
}

/** The parser message quotes the input, which is instruction text: only the failure kind is kept. */
private fun SerializationException.withoutInput(): Throwable =
    IllegalArgumentException("Invalid learning call arguments (${this::class.simpleName.orEmpty()})")

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
