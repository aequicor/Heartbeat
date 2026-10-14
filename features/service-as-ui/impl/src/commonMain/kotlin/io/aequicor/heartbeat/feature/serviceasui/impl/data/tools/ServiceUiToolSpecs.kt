package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Interface tool declarations; mutations, display and agent calls use contribution-owned automatic review. */
internal val ServiceUiToolSpecs: List<AgentToolSpec> = listOf(
    AgentToolSpec(
        ServiceUiTools.REFERENCE,
        "Reference of the interface spec format: places, nodes, actions, bindings, limits. Read it before ui_put.",
        schema(),
    ),
    AgentToolSpec(
        ServiceUiTools.LIST,
        "Interfaces of the library and the owners (harnesses) that can own them, with the methods they publish.",
        schema(),
    ),
    AgentToolSpec(
        ServiceUiTools.GET,
        "One interface of the library with its spec and revision.",
        schema(listOf("id")) { text("id", "Interface id from ui_list, such as harness:<owner id>:<name>.") },
    ),
    AgentToolSpec(
        ServiceUiTools.PUT,
        "Creates or replaces an interface of a harness. The spec is checked against the format and the owner's " +
            "methods; errors come back with JSON paths.",
        schema(listOf("owner", "name", "placement", "title", "spec")) {
            text("owner", "Owner from ui_list: its ref (harness:<id>) or its name.")
            text("name", "Short lowercase slug, unique for the owner; other interfaces open it by this name.")
            choice(
                "placement",
                "Use card for a mini-app inside the conversation; composer for compact status.",
                listOf("card", "composer", "start", "panel", "modal"),
            )
            text("title", "Title for people, at most 80 characters.")
            text("description", "What it is for, at most 500 characters.")
            putJsonObject("launchers") {
                put("type", "array")
                put("description", "Panel and modal interfaces only: slots that get a button opening it.")
                putJsonObject("items") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("composer"))
                        add(JsonPrimitive("start"))
                    }
                }
            }
            putJsonObject("spec") {
                put("type", "object")
                put("description", "The spec document, see ui_reference.")
            }
            putJsonObject("expectedRevision") {
                put("type", "integer")
                put("description", "Current revision when replacing an interface; omit or 0 to create one.")
            }
        },
        AgentToolAction.Edit,
    ),
    AgentToolSpec(
        ServiceUiTools.DELETE,
        "Deletes an interface of the library.",
        schema(listOf("id", "expectedRevision")) {
            text("id", "Interface id from ui_list.")
            putJsonObject("expectedRevision") {
                put("type", "integer")
                put("description", "Its current revision.")
            }
        },
        AgentToolAction.Edit,
    ),
    AgentToolSpec(
        ServiceUiTools.SHOW,
        "Shows the chat's single live library app with arguments; repeated shows replace that same app. " +
            "An inline spec instead adds an anonymous snapshot without methods or a web bridge.",
        schema(listOf("title")) {
            text("title", "Card title, at most 80 characters.")
            putJsonObject("spec") {
                put("type", "object")
                put("description", "Inline spec, see ui_reference. Give either spec or surface.")
            }
            text("surface", "Id of a library interface from ui_list. Give either spec or surface.")
            putJsonObject("args") {
                put("type", "object")
                put("description", "Arguments of a library interface, readable as args.")
            }
        },
    ),
    AgentToolSpec(
        ServiceUiTools.CALL,
        "Calls a method an owner publishes, as a button of its interface would, to check what it returns.",
        schema(listOf("owner", "method")) {
            text("owner", "Owner from ui_list: its ref (harness:<id>) or its name.")
            text("method", "Method name from ui_list.")
            putJsonObject("args") {
                put("type", "object")
                put("description", "Arguments of the method.")
            }
        },
        AgentToolAction.Command,
    ),
)

private fun schema(required: List<String> = emptyList(), properties: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties", properties)
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

private fun JsonObjectBuilder.text(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

private fun JsonObjectBuilder.choice(name: String, description: String, values: List<String>) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }
}
