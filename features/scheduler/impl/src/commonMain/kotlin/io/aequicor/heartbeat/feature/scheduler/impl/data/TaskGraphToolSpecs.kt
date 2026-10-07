package io.aequicor.heartbeat.feature.scheduler.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Exact, bounded wire schema for the graph model; execution identity and approvals are supplied by the host. */
internal object TaskGraphToolSpecs {
    const val CREATE = "scheduler_create_graph"
    const val LIST = "scheduler_list_graphs"
    const val GET = "scheduler_get_graph"
    const val CANCEL = "scheduler_cancel_graph"
    const val RESOLVE = "scheduler_resolve_interrupted_task"

    private const val GRAPH_ID = """"graph_id":{"type":"string","pattern":"^[a-z0-9_-]{1,32}$"}"""
    val all = listOf(
        spec(
            CREATE,
            "Approve and run an immutable graph of dependent and parallel command or agent tasks.",
            """
            {"type":"object","additionalProperties":false,"required":["graph_id","tasks"],"properties":{
              $GRAPH_ID,
              "tasks":{"type":"array","minItems":1,"maxItems":64,"items":{
                "type":"object","additionalProperties":false,"required":["id","action"],"properties":{
                  "id":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},
                  "action":{"oneOf":[
                    {"type":"object","additionalProperties":false,"required":["kind","command"],"properties":{
                      "kind":{"const":"command"},"command":{"type":"string","maxLength":4000},
                      "timeoutSeconds":{"type":"integer","minimum":1,"maximum":21600}}},
                    {"type":"object","additionalProperties":false,"required":["kind","prompt"],"properties":{
                      "kind":{"const":"agent"},"prompt":{"type":"string","maxLength":8000},
                      "title":{"type":"string","maxLength":60}}}]},
                  "dependencies":{"type":"object","additionalProperties":false,"required":["kind","tasks"],
                    "properties":{"kind":{"enum":["all","any"]},"tasks":{"type":"array","items":{
                      "type":"object","additionalProperties":false,"required":["task"],"properties":{
                        "task":{"type":"string"},"outcome":{"enum":["succeeded","finished"]}}}}}}
                }}}
            }}
        """,
            AgentToolAction.Command,
        ),
        spec(
            LIST,
            "List graphs owned by this session and their task states.",
            """{"type":"object","properties":{},"additionalProperties":false}""",
        ),
        spec(
            GET,
            "Read a saved graph, assignments, results and execution IDs; task_id selects one task.",
            """{"type":"object","required":["graph_id"],"properties":{$GRAPH_ID,"task_id":{"type":"string"}}}""",
        ),
        spec(
            CANCEL,
            "Cancel this session's graph and stop its remaining work.",
            """{"type":"object","required":["graph_id"],"properties":{$GRAPH_ID}}""",
            AgentToolAction.Command,
        ),
        spec(
            RESOLVE,
            "Resolve an interrupted attempt after inspecting its actual effects; reuses the graph approval.",
            """
            {"type":"object","additionalProperties":false,
             "required":["graph_id","task_id","execution","decision","explanation"],"properties":{
              $GRAPH_ID,"task_id":{"type":"string"},"execution":{"type":"string"},
              "decision":{"enum":["retry","succeeded","failed"]},
              "explanation":{"type":"string","minLength":1,"maxLength":8192}}}
        """,
            AgentToolAction.Command,
        ),
    )

    private fun spec(
        name: String,
        description: String,
        schema: String,
        action: AgentToolAction = AgentToolAction.Read,
    ) = AgentToolSpec(name, description, Json.parseToJsonElement(schema) as JsonObject, action)
}
