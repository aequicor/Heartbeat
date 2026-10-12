package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOffloadEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationTools
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Keeps tool output longer than the settings' threshold out of the context: the output is stored and the agent gets
 * its beginning, its end and how to read the rest with [ContextOptimizationTools.RECALL].
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ContextOutputOffload(
    private val view: ContextOptimizationView,
    private val toggles: FeatureToggles,
    private val outputs: ContextOutputs,
) : ToolOutputOffload {
    override suspend fun offload(session: SessionRef, tool: String, text: String): String? {
        if (tool == ContextOptimizationTools.RECALL || !isOffloadEnabled(toggles)) return null
        val threshold = (view.state.value as? ContextOptimizationState.Ready)?.settings?.offloadThresholdChars
        if (threshold == null || text.length <= threshold) return null
        // A threshold below the stub size would make the output longer.
        if (text.length <= HEAD_CHARS + TAIL_CHARS + NOTE_CHARS) return null
        val stored = outputs.put(StoredOutput(session, tool, text))
        return text.take(HEAD_CHARS) + "\n…\n" + text.takeLast(TAIL_CHARS) +
            "\n[The output had ${text.length} characters; only its beginning and end are shown. Read the first " +
            "${stored.chars} with ${ContextOptimizationTools.RECALL}(id=\"${stored.id}\", offset=$HEAD_CHARS).]"
    }

    private companion object {
        const val HEAD_CHARS = 2_000
        const val TAIL_CHARS = 1_000
        const val NOTE_CHARS = 300
    }
}

/** Reads parts of offloaded outputs of the calling session only. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ContextRecallTools(private val toggles: FeatureToggles, private val outputs: ContextOutputs) :
    AgentToolContribution {
    private val log = Log.tag("ContextRecallTools")
    override val group: String = "context"
    override val title: String = "Контекст"
    override val catalog: List<ToolCatalogEntry> get() = listOf(RecallSpec).toolCatalog()
    override val isDetachedSupported: Boolean = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isOffloadEnabled(toggles)) listOf(RecallSpec) else emptyList()

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!isOffloadEnabled(toggles)) return AgentToolResult("Offloaded outputs are disabled", isError = true)
        val id = (arguments["id"] as? JsonPrimitive)?.contentOrNull
        val output = id?.let { outputs.get(it) }?.takeIf { it.session == context.session }
            ?: return AgentToolResult("No stored output with this id in this session", isError = true)
        val offset = ((arguments["offset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, output.text.length)
        val length = ((arguments["length"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LENGTH).coerceIn(1, MAX_LENGTH)
        val end = (offset + length).coerceAtMost(output.text.length)
        log.v { "recall an offloaded output: chars=${end - offset}" }
        val next = if (end < output.text.length) " Continue with offset=$end." else " This is the end."
        val range = "\n[Characters $offset-$end of ${output.text.length}.$next]"
        return AgentToolResult(output.text.substring(offset, end) + range)
    }

    private companion object {
        const val DEFAULT_LENGTH = 8_000
        const val MAX_LENGTH = 20_000
    }
}

private suspend fun isOffloadEnabled(toggles: FeatureToggles): Boolean =
    toggles.get(ContextOptimizationEnabled) && toggles.get(ContextOffloadEnabled)

private val RecallSpec = AgentToolSpec(
    ContextOptimizationTools.RECALL,
    "Read part of a long tool output that was kept out of the context",
    Json.parseToJsonElement(
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id"],
          "properties": {
            "id": {"type": "string", "description": "Id from the shortened output"},
            "offset": {"type": "integer", "minimum": 0, "description": "First character to read"},
            "length": {"type": "integer", "minimum": 1, "maximum": 20000, "description": "Characters to read"}
          }
        }
        """.trimIndent(),
    ).jsonObject,
)
