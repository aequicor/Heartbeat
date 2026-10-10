package io.aequicor.heartbeat.feature.checklist.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.checklist.api.Checklist
import io.aequicor.heartbeat.feature.checklist.api.ChecklistCompletionMode
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.checklist.api.ChecklistField
import io.aequicor.heartbeat.feature.checklist.api.ChecklistInput
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.checklist.impl.domain.ChecklistMachine
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Tools create/read only. Supplying session identities or answering as the user is never supported. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ChecklistAgentTools(
    private val machine: Lazy<ChecklistMachine>,
    private val storage: Lazy<ChecklistStorage>,
    private val toggles: FeatureToggles,
) : AgentToolContribution {
    override val group: String = "checklist"
    override val title: String = "Чек-листы"
    override val catalog: List<ToolCatalogEntry> get() = checklistToolSpecs().toolCatalog()

    private val log = Log.tag("ChecklistTools")
    override val isDetachedSupported: Boolean = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(ChecklistEnabled)) checklistToolSpecs() else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String = if (toggles.get(ChecklistEnabled)) {
        "Use checklist_create for persistent user checks embedded in your reply. Only the user fills them. " +
            "ResumeSession resumes you after completion; MarkSessionReady marks the work ready without a turn. " +
            "After a defect report and fixes create a new readiness checklist. Finish your turn after creation. " +
            "Use the same key when retrying creation. checklist_get returns frozen answers after completion."
    } else {
        ""
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(ChecklistEnabled)) return AgentToolResult("Checklists are disabled", true)
        return try {
            when (name) {
                "checklist_create" -> create(context, arguments)

                "checklist_get" -> {
                    val id = arguments["id"]?.jsonPrimitive?.content
                    val card = (machine.value.state.value as? ChecklistState.Ready)?.journal?.cards
                        ?.firstOrNull { it.id == id && it.session == context.session }
                    if (card == null) {
                        AgentToolResult("Checklist not found in this session", true)
                    } else {
                        AgentToolResult(Json.encodeToString(card))
                    }
                }

                else -> AgentToolResult("Unknown checklist tool", true)
            }
        } catch (e: IllegalArgumentException) {
            log.w(e.withoutChecklistText()) { "Checklist specification rejected" }
            AgentToolResult("Invalid checklist specification", true)
        }
    }

    private suspend fun create(context: AgentToolContext, args: JsonObject): AgentToolResult {
        val request = context.request ?: return AgentToolResult("A host-owned chat request is required", true)
        val spec = specification(args)
        if (spec.mode == ChecklistCompletionMode.ResumeSession && !toggles.get(SchedulerEnabled)) {
            return AgentToolResult("ResumeSession requires the scheduler to be enabled", true)
        }
        return create(context, request, spec)
    }

    private suspend fun create(
        context: AgentToolContext,
        request: io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId,
        spec: Creation,
    ): AgentToolResult {
        val queue = machine.value
        val state = queue.state.value as? ChecklistState.Ready ?: return AgentToolResult("Checklists are loading", true)
        val existing = state.journal.cards.firstOrNull {
            it.session == context.session && it.request == request && it.creationKey == spec.key
        }
        if (existing != null) return savedResult(existing)
        val card = Checklist(
            id = Uuid.random().toHexString(), session = context.session, request = request,
            turn = context.turn, callId = context.callId?.value, workspace = context.workspace,
            target = context.target, title = spec.title, fields = spec.fields, mode = spec.mode,
            creationKey = spec.key, historyTurn = context.historyTurn ?: context.turn,
        )
        if (queue.send(ChecklistIntent.Public.Create(card)) != SendResult.Accepted) {
            val duplicate = (queue.state.value as? ChecklistState.Ready)?.journal?.cards?.firstOrNull {
                it.session == context.session && it.request == request && it.creationKey == spec.key
            }
            return duplicate?.let { savedResult(it) }
                ?: AgentToolResult("The chat generation is not synchronized yet; retry creation", true)
        }
        return savedResult(card)
    }

    private suspend fun savedResult(card: Checklist): AgentToolResult {
        if ((machine.value.state.value as? ChecklistState.Ready)?.hasFailed == true) {
            machine.value.send(ChecklistIntent.Public.Retry)
        }
        val saved = withTimeoutOrNull(10.seconds) {
            storage.value.observe().first { journal -> journal?.cards?.any { it.id == card.id } == true }
        }
        return if (saved == null) {
            AgentToolResult("Checklist save is unconfirmed; retry with the same key", true)
        } else {
            AgentToolResult("Checklist ${card.id} created. End your turn and wait for the user.")
        }
    }
}

private fun checklistToolSpecs(): List<AgentToolSpec> = listOf(
    AgentToolSpec(
        "checklist_create",
        "Create an inline checklist for the user",
        Json.parseToJsonElement(CREATE_SCHEMA).jsonObject,
    ),
    AgentToolSpec(
        "checklist_get",
        "Read a checklist and its user answers in this session",
        Json.parseToJsonElement(GET_SCHEMA).jsonObject,
    ),
)

private const val GET_SCHEMA = """
{
  "type": "object",
  "properties": {
    "id": {
      "type": "string"
    }
  },
  "required": [
    "id"
  ],
  "additionalProperties": false
}
"""
private const val CREATE_SCHEMA = """
{
  "type": "object",
  "required": [
    "key",
    "title",
    "fields"
  ],
  "additionalProperties": false,
  "properties": {
    "key": {
      "type": "string",
      "description": "Stable creation key within this request; reuse on retries"
    },
    "title": {
      "type": "string"
    },
    "mode": {
      "type": "string",
      "enum": [
        "ResumeSession",
        "MarkSessionReady"
      ]
    },
    "fields": {
      "type": "array",
      "minItems": 1,
      "maxItems": 32,
      "items": {
        "type": "object",
        "required": [
          "id",
          "title",
          "type"
        ],
        "additionalProperties": false,
        "properties": {
          "id": {
            "type": "string"
          },
          "title": {
            "type": "string"
          },
          "type": {
            "type": "string",
            "enum": [
              "SingleChoice",
              "MultiChoice",
              "Text"
            ]
          },
          "required": {
            "type": "boolean",
            "default": true
          },
          "choices": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "id": {
                  "type": "string"
                },
                "title": {
                  "type": "string"
                }
              },
              "required": [
                "id",
                "title"
              ],
              "additionalProperties": false
            }
          },
          "min": {
            "type": "integer",
            "minimum": 0
          },
          "max": {
            "type": "integer",
            "minimum": 0
          }
        }
      }
    }
  }
}
"""

private data class Creation(
    val key: String,
    val title: String,
    val fields: List<ChecklistField>,
    val mode: ChecklistCompletionMode,
)

private fun specification(args: JsonObject): Creation {
    val key = requireNotNull(args["key"]).jsonPrimitive.content
    require(key.isNotBlank() && key.length <= MAX_KEY)
    val fields = Json.decodeFromJsonElement<List<ChecklistField>>(requireNotNull(args["fields"]))
    require(
        fields.size in 1..MAX_FIELDS && fields.all { it.choices.size <= MAX_CHOICES && it.title.length <= MAX_TITLE },
    )
    require(fields.none { it.type == ChecklistInput.MultiChoice && it.isRequired && it.max == 0 })
    val title = requireNotNull(args["title"]).jsonPrimitive.content
    require(title.length <= MAX_TITLE)
    val mode = args["mode"]?.jsonPrimitive?.content?.let(ChecklistCompletionMode::valueOf)
        ?: ChecklistCompletionMode.ResumeSession
    return Creation(key, title, fields, mode)
}

private const val MAX_KEY = 128
private const val MAX_FIELDS = 32
private const val MAX_CHOICES = 64
private const val MAX_TITLE = 2000
