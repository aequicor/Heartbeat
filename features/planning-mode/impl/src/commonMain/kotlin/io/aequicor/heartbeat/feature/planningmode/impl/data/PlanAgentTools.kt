package io.aequicor.heartbeat.feature.planningmode.impl.data

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
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanEdit
import io.aequicor.heartbeat.feature.planningmode.api.PlanLimits
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningMachineKey
import io.aequicor.heartbeat.feature.planningmode.api.PlanningModeEnabled
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningTools
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.planningmode.api.applyEdit
import io.aequicor.heartbeat.feature.planningmode.api.isPlanSectionHeading
import io.aequicor.heartbeat.feature.planningmode.api.renderPlanBlock
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

/**
 * The plan tools of a chat's agent. They exist in every session while [PlanningModeEnabled] is on, because some
 * engines freeze their tools when a thread starts, and answer only in sessions bound to a plan. [PlanningTools.READ]
 * re-reads the plan file, so edits made outside the studio reach the agent; [PlanningTools.WRITE] changes it
 * against the revision the agent last read and refuses when the file was edited outside the studio meanwhile;
 * [PlanningTools.PROPOSE] shows the plan card. They only touch profile data, so they are read actions. Plan texts are
 * user data and never logged.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class PlanAgentTools(
    private val view: PlanningView,
    private val machines: MachineRegistry,
    private val files: PlanFiles,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) : AgentToolContribution {
    private val log = Log.tag("PlanAgentTools")
    override val group: String = "planning"
    override val title: String = "План"
    override val catalog: List<ToolCatalogEntry> get() = PlanToolSpecs.toolCatalog()
    override val isDetachedSupported: Boolean = true
    override val hasIndependentInstructions: Boolean = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(PlanningModeEnabled)) PlanToolSpecs else emptyList()

    override suspend fun instructions(scope: AgentToolScope): String {
        if (!toggles.get(PlanningModeEnabled)) return ""
        val isDeclared = scope.declared?.containsAll(PlanningTools.ALL) ?: true
        val plan = scope.session.takeIf { scope.isRefreshedPerTurn }?.let { ready()?.planOf(it) }
        return listOfNotNull(GUIDE.takeIf { isDeclared }, plan?.let(::renderPlanBlock)).joinToString("\n\n")
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(PlanningModeEnabled)) return AgentToolResult("Planning mode is disabled", isError = true)
        val plan = ready()?.planOf(context.session)?.takeIf { it.mode != PlanMode.Detached }
            ?: return AgentToolResult("Planning mode is not on in this chat", isError = true)
        log.v { "plan tool $name" }
        return when (name) {
            PlanningTools.READ -> read(plan)
            PlanningTools.WRITE -> write(plan, arguments)
            PlanningTools.PROPOSE -> propose(plan, arguments)
            else -> AgentToolResult("Unknown plan tool", isError = true)
        }
    }

    /** The plan as stored on disk; a file changed outside the studio becomes a new revision first. */
    private suspend fun read(plan: Plan): AgentToolResult = when (val sync = syncExternal(plan)) {
        is Sync.Refused -> sync.result

        is Sync.Current -> AgentToolResult(
            describe(sync.plan) + "\n\n" + sync.plan.text.ifBlank { "(the plan is empty)" },
        )
    }

    /**
     * [plan] after taking over its file when it was changed outside the studio: the change becomes a new revision by
     * [PlanAuthor.External]. A file the machine cannot take over is reported instead, so the agent never works from a
     * text that differs from the file without knowing it.
     */
    private suspend fun syncExternal(plan: Plan): Sync {
        val file = files.read(plan.chat)
        val isChanged = file != null && file.isExternal &&
            (file.isOversized || applyEdit(plan.text, PlanEdit.Replace(file.text)) != plan.text)
        if (file == null || !isChanged) return Sync.Current(plan, isChanged = false)
        val length = file.text.length
        if (file.isOversized || length > PlanLimits.MAX_CHARS) {
            log.w { "external plan edit too long: chat=${plan.chat} length=$length" }
            return Sync.Refused(
                "The plan file was changed outside the studio and is longer than ${PlanLimits.MAX_CHARS} " +
                    "characters, so it cannot be used. Ask the user to shorten it; the plan stays at revision " +
                    "${plan.revision} until then",
            )
        }
        val edit = PlanEdit.Replace(file.text)
        val result = machines.send(
            PlanningMachineKey,
            PlanningIntent.Public.Write(plan.chat, edit, plan.revision, PlanAuthor.External, clock.now()),
        )
        if (result != SendResult.Accepted) {
            log.w { "external plan edit not taken over: chat=${plan.chat} result=$result" }
            return Sync.Refused(
                if (result == SendResult.NotRunning) {
                    NOT_RUNNING
                } else {
                    "The plan file was changed outside the studio while the plan changed too; call " +
                        "${PlanningTools.READ} again"
                },
            )
        }
        log.i { "external plan edit picked up: chat=${plan.chat}" }
        return Sync.Current(ready()?.plans?.get(plan.chat) ?: plan, isChanged = true)
    }

    private suspend fun write(plan: Plan, arguments: JsonObject): AgentToolResult {
        val base = (arguments["base_revision"] as? JsonPrimitive)?.longOrNull
        val content = arguments.text("content")
        val section = arguments.text("section")?.trim()
        val edit = when {
            content == null -> null
            section == null -> PlanEdit.Replace(content)
            isPlanSectionHeading(section) -> PlanEdit.Section(section, content)
            else -> null
        }
        if (base == null || edit == null) {
            return AgentToolResult(
                "Pass base_revision and content; section must be one markdown heading line such as \"## Steps\" " +
                    "(1 to 6 '#' followed by a space)",
                isError = true,
            )
        }
        return when (val sync = syncExternal(plan)) {
            is Sync.Refused -> sync.result
            is Sync.Current -> if (sync.isChanged) changedOutside(sync.plan) else write(sync.plan, edit, base)
        }
    }

    private fun changedOutside(plan: Plan) = AgentToolResult(
        "The plan file was changed outside the studio; the plan is now at revision ${plan.revision}. " +
            "Call ${PlanningTools.READ}, merge your change and write again with that base_revision",
        isError = true,
    )

    private suspend fun write(plan: Plan, edit: PlanEdit, base: Long): AgentToolResult {
        val text = applyEdit(plan.text, edit)
        return when {
            base != plan.revision -> AgentToolResult(
                "The plan changed and is now at revision ${plan.revision}. " +
                    "Call ${PlanningTools.READ}, merge your change and write again with that base_revision",
                isError = true,
            )

            text == plan.text -> AgentToolResult("The plan already has this content (revision ${plan.revision}).")

            text.length > PlanLimits.MAX_CHARS -> AgentToolResult(
                "The plan would have ${text.length} characters; keep it under ${PlanLimits.MAX_CHARS}",
                isError = true,
            )

            else -> send(PlanningIntent.Public.Write(plan.chat, edit, base, PlanAuthor.Agent, clock.now())) {
                "Plan saved as revision ${plan.revision + 1}."
            }
        }
    }

    private suspend fun propose(plan: Plan, arguments: JsonObject): AgentToolResult {
        val revision = (arguments["revision"] as? JsonPrimitive)?.longOrNull
        return when {
            plan.mode != PlanMode.Planning -> AgentToolResult("The plan is already being implemented", isError = true)

            revision != plan.revision -> AgentToolResult(
                "The plan is at revision ${plan.revision}; call ${PlanningTools.READ} and propose that revision",
                isError = true,
            )

            plan.status == PlanStatus.Approved -> AgentToolResult("Revision $revision is already approved.")

            plan.status == PlanStatus.Proposed -> AgentToolResult(PROPOSED)

            else -> send(PlanningIntent.Public.Propose(plan.chat, plan.revision)) { PROPOSED }
        }
    }

    private suspend fun send(intent: PlanningIntent.Public, success: () -> String): AgentToolResult =
        when (machines.send(PlanningMachineKey, intent)) {
            SendResult.Accepted -> AgentToolResult(success())

            SendResult.NotRunning -> AgentToolResult(NOT_RUNNING, isError = true)

            SendResult.Ignored -> AgentToolResult(
                "The plan changed meanwhile; call ${PlanningTools.READ} and retry",
                isError = true,
            )
        }

    /** The plan of a call, current or refused with the reason for the agent. */
    private sealed interface Sync {
        data class Current(val plan: Plan, val isChanged: Boolean) : Sync

        data class Refused(val result: AgentToolResult) : Sync {
            constructor(message: String) : this(AgentToolResult(message, isError = true))
        }
    }

    private fun describe(plan: Plan): String {
        val status = when (plan.status) {
            PlanStatus.Draft -> "draft"
            PlanStatus.Proposed -> "proposed"
            PlanStatus.Approved -> "approved"
        }
        val mode = if (plan.mode == PlanMode.Planning) "planning (read-only)" else "implementing"
        val author = when (plan.updatedBy) {
            PlanAuthor.Agent -> "you"
            PlanAuthor.User -> "the user"
            PlanAuthor.External -> "an edit outside the studio"
        }
        return "Plan revision ${plan.revision}, $status, mode $mode; last changed by $author."
    }

    private fun ready(): PlanningState.Ready? = view.state.value as? PlanningState.Ready
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private const val NOT_RUNNING = "Plans are not available right now; try again later"

private const val PROPOSED =
    "The plan card is shown to the user. End your turn now and wait for them to approve, refine or implement it."

private const val GUIDE =
    "When planning mode is on in a chat, the plan of the task is attached to your prompts. Re-read it with " +
        "plan_read, change it with plan_write and ask the user to review it with plan_propose. The tools answer " +
        "only in chats with planning mode."

private val PlanToolSpecs = listOf(
    AgentToolSpec(
        PlanningTools.READ,
        "Read the current plan of this chat from disk, with its revision, status and mode",
        Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject,
        resultChars = PlanLimits.MAX_CHARS + RESULT_HEADER_CHARS,
    ),
    AgentToolSpec(
        PlanningTools.WRITE,
        "Replace the plan of this chat, or one markdown section of it, starting from the revision you last read",
        Json.parseToJsonElement(WRITE_SCHEMA).jsonObject,
    ),
    AgentToolSpec(
        PlanningTools.PROPOSE,
        "Ask the user to review the current revision of the plan; end the turn after calling it",
        Json.parseToJsonElement(PROPOSE_SCHEMA).jsonObject,
    ),
)

private const val RESULT_HEADER_CHARS = 512

private const val WRITE_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "required": ["base_revision", "content"],
  "properties": {
    "base_revision": {"type": "integer", "description": "Revision of the plan you last read"},
    "content": {"type": "string", "description": "Markdown: the whole plan, or the body of the section"},
    "section": {
      "type": "string",
      "description": "Optional markdown heading such as \"## Steps\": replace only that section, add it if missing, remove it when content is empty"
    }
  }
}
"""

private const val PROPOSE_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "required": ["revision", "summary"],
  "properties": {
    "revision": {"type": "integer", "description": "Revision of the plan to review"},
    "summary": {"type": "string", "description": "One or two sentences on what the plan does and what needs a decision"}
  }
}
"""
