package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionOrigin
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextLedgerEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationMachineKey
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationTools
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.LedgerUpdate
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.percent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.render
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * The agent's context tools for long-running work: [ContextOptimizationTools.STATUS] reads occupancy, the ledger and
 * pending compaction, [ContextOptimizationTools.CHECKPOINT] records progress in the ledger and
 * [ContextOptimizationTools.COMPACT] asks to compact the context once the current turn ends. None of them touches
 * files or runs commands, so they are read actions. Engines that rebuild instructions every turn also read the
 * ledger here.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ContextAgentTools(
    private val view: ContextOptimizationView,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) : AgentToolContribution {
    private val log = Log.tag("ContextAgentTools")
    override val group: String = "context"
    override val title: String = "Контекст"
    override val catalog: List<ToolCatalogEntry> get() = ContextToolSpecs.toolCatalog()
    override val isDetachedSupported: Boolean = true
    override val hasIndependentInstructions: Boolean = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isEnabled()) ContextToolSpecs else emptyList()

    override suspend fun instructions(scope: AgentToolScope): String {
        if (!isEnabled()) return ""
        val isDeclared = scope.declared?.containsAll(ContextToolSpecs.map { it.name }) ?: true
        val ledger = scope.session.takeIf { scope.isRefreshedPerTurn }?.let { ready()?.ledgers?.get(it)?.render() }
        return listOfNotNull(GUIDE.takeIf { isDeclared }, ledger).joinToString("\n\n")
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!isEnabled()) return AgentToolResult("Context tools are disabled", isError = true)
        log.v { "context tool $name" }
        return when (name) {
            ContextOptimizationTools.STATUS -> AgentToolResult(status(context))
            ContextOptimizationTools.CHECKPOINT -> checkpoint(context, arguments)
            ContextOptimizationTools.COMPACT -> compact(context, arguments)
            else -> AgentToolResult("Unknown context tool", isError = true)
        }
    }

    private fun status(context: AgentToolContext): String {
        val state = ready() ?: return "Context optimization is loading."
        val usage = state.usage[context.session]
        val occupancy = usage?.let { "${it.percent}% (${it.usedTokens} of ${it.capacityTokens} tokens)" } ?: "unknown"
        val pending = state.pending[context.session] ?: state.running[context.session]
        return buildString {
            append("Context occupancy after the last turn: ").append(occupancy)
            append("\nAutomatic compaction threshold: ").append(state.settings.thresholdPercent).append('%')
            append("\nPending compaction: ").append(pending?.method?.name ?: "none")
            append("\n\n").append(state.ledgers[context.session]?.render() ?: "The progress ledger is empty.")
        }
    }

    private suspend fun checkpoint(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val update = LedgerUpdate(
            goal = arguments.text("goal"),
            done = arguments.texts("done"),
            next = arguments["next"]?.let { arguments.texts("next") },
            facts = arguments.texts("facts"),
        )
        val intent = ContextOptimizationIntent.Public.UpdateLedger(context.session, update, clock.now())
        return if (machines.send(ContextOptimizationMachineKey, intent) == SendResult.Accepted) {
            AgentToolResult("Progress recorded. It stays available after the context is compacted.")
        } else {
            AgentToolResult("The ledger is not ready; retry later", isError = true)
        }
    }

    private suspend fun compact(context: AgentToolContext, arguments: JsonObject): AgentToolResult {
        val intent = ContextOptimizationIntent.Public.Compact(
            context.session,
            CompactionOrigin.Agent,
            arguments.text("instructions"),
            sourceRequest = context.request,
        )
        return if (machines.send(ContextOptimizationMachineKey, intent) == SendResult.Accepted) {
            AgentToolResult(
                "Compaction is scheduled for when this turn ends. Record progress with " +
                    "${ContextOptimizationTools.CHECKPOINT} first, then finish the current step and end the turn.",
            )
        } else {
            AgentToolResult("A compaction of this session is already running", isError = true)
        }
    }

    private fun ready(): ContextOptimizationState.Ready? = view.state.value as? ContextOptimizationState.Ready

    private suspend fun isEnabled(): Boolean =
        toggles.get(ContextOptimizationEnabled) && toggles.get(ContextLedgerEnabled)
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.texts(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private val ContextToolSpecs = listOf(
    AgentToolSpec(
        ContextOptimizationTools.STATUS,
        "Show how full the context window is, the progress ledger and any pending compaction",
        Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject,
    ),
    AgentToolSpec(
        ContextOptimizationTools.CHECKPOINT,
        "Record progress in the ledger that survives context compaction: goal, finished items, next steps, facts",
        Json.parseToJsonElement(CHECKPOINT_SCHEMA).jsonObject,
    ),
    AgentToolSpec(
        ContextOptimizationTools.COMPACT,
        "Compact the context window after this turn ends, optionally with what the summary must keep",
        Json.parseToJsonElement(COMPACT_SCHEMA).jsonObject,
    ),
)

private const val GUIDE =
    "For long or repeated work keep a progress ledger: after each finished item call context_checkpoint with what " +
        "was done, the next steps and facts you will need later. When context_status shows the context is nearly " +
        "full, or a large part of the work is finished, call context_compact and end the turn; the ledger is shown " +
        "to you again after compaction."

private const val CHECKPOINT_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "goal": {"type": "string", "description": "The overall goal; replaces the previous one"},
    "done": {"type": "array", "items": {"type": "string"}, "description": "Items finished since the last checkpoint"},
    "next": {"type": "array", "items": {"type": "string"}, "description": "The remaining plan; replaces it"},
    "facts": {"type": "array", "items": {"type": "string"}, "description": "Facts needed later"}
  }
}
"""

private const val COMPACT_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "instructions": {"type": "string", "description": "What the summary must keep"}
  }
}
"""
