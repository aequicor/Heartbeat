package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Output of one tool call as the model sees it; [resources] are also shown to the user. */
internal data class KoogToolResult(
    val text: String,
    val isFailed: Boolean,
    val resources: List<ResourceRef> = emptyList(),
)

/**
 * One tool offered to the model. A [isMutating] tool changes the user's machine (files, processes) and runs only
 * after the session's approval policy allows it; [target] is what the user approves and must describe the whole
 * effect of the call.
 */
internal interface KoogTool {
    val descriptor: ToolDescriptor

    val isMutating: Boolean get() = false

    /** Human-readable subject of the call (path, command) shown in the approval request. */
    fun target(args: JsonObject): String = ""

    /** Optional details under [target] in the approval request, e.g. the text a file edit inserts. */
    fun details(args: JsonObject): String? = null

    /**
     * Executes the call; expected failures (bad input, missing file) are results with [KoogToolResult.isFailed],
     * never exceptions. [CancellationException][kotlinx.coroutines.CancellationException] propagates.
     */
    suspend fun run(args: JsonObject): KoogToolResult
}

/** Tools of one turn, addressed by name. */
internal class KoogToolbox(tools: List<KoogTool>) {
    private val byName = tools.associateBy { it.descriptor.name }

    val descriptors: List<ToolDescriptor> = tools.map { it.descriptor }

    operator fun get(name: String): KoogTool? = byName[name]

    fun isEmpty(): Boolean = byName.isEmpty()
}

internal fun JsonObject.argText(name: String): String = (this[name] as? JsonPrimitive)?.content.orEmpty()

internal fun JsonObject.argInt(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.argFlag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
