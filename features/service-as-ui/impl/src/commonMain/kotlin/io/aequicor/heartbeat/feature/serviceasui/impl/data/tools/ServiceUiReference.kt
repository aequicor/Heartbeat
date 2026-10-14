package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiAuthoringDocument
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiAuthoringKnowledge
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.uiSpecReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads bundled authoring knowledge without a workspace, owner, library or execution permission. Calls without
 * arguments include all documents so sessions with a frozen, argument-free schema can access every reference.
 */
internal suspend fun serviceUiReference(
    arguments: JsonObject,
    knowledge: UiAuthoringKnowledge,
    methodResultBytes: Int,
    callSeconds: Int,
): AgentToolResult {
    if (arguments.keys.any { it != "topic" }) {
        return AgentToolResult(
            "Invalid reference request: only the optional topic argument is accepted.",
            isError = true,
        )
    }
    val value = arguments["topic"]
    val topic = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    val document = UiAuthoringDocument.entries.firstOrNull { it.topic == topic }
    if (value != null && topic != "spec" && document == null) {
        return AgentToolResult(
            "Invalid reference topic: use one of ${ServiceUiReferenceTopics.joinToString()}.",
            isError = true,
        )
    }
    val text = when {
        topic == "spec" -> uiSpecReference(methodResultBytes, callSeconds)

        document != null -> knowledge.read(document)

        else -> buildList {
            add(uiSpecReference(methodResultBytes, callSeconds))
            UiAuthoringDocument.entries.forEach { add(knowledge.read(it)) }
        }.joinToString("\n\n")
    }
    return AgentToolResult(text)
}

/** The spec is generated from runtime limits; the other topics are immutable packaged resources. */
internal val ServiceUiReferenceTopics: List<String> = listOf("spec") + UiAuthoringDocument.entries.map { it.topic }
