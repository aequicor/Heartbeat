package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Operation-specific declarations keep irrelevant arguments and host authorization tokens out of the model API. */
internal object ExternalBrowserToolSpecs {
    val operations = mapOf(
        "ex_control_tabs" to ExternalOperation.Tabs,
        "ex_control_active_tab" to ExternalOperation.ActiveTab,
        "ex_control_new_tab" to ExternalOperation.NewTab,
        "ex_control_close_tab" to ExternalOperation.CloseTab,
        "ex_control_request_tab" to ExternalOperation.RequestTab,
        "ex_control_release" to ExternalOperation.Release,
        "ex_control_open" to ExternalOperation.Open,
        "ex_control_search" to ExternalOperation.Search,
        "ex_control_back" to ExternalOperation.Back,
        "ex_control_forward" to ExternalOperation.Forward,
        "ex_control_reload" to ExternalOperation.Reload,
        "ex_control_activate" to ExternalOperation.Activate,
        "ex_control_read" to ExternalOperation.Read,
        "ex_control_screenshot" to ExternalOperation.Screenshot,
        "ex_control_click" to ExternalOperation.Click,
        "ex_control_type" to ExternalOperation.Type,
        "ex_control_key" to ExternalOperation.Key,
        "ex_control_scroll" to ExternalOperation.Scroll,
        "ex_control_drag" to ExternalOperation.Drag,
    )
    private val commands = setOf(
        ExternalOperation.CloseTab,
        ExternalOperation.Back,
        ExternalOperation.Forward,
        ExternalOperation.Reload,
        ExternalOperation.Click,
        ExternalOperation.Type,
        ExternalOperation.Key,
        ExternalOperation.Scroll,
        ExternalOperation.Drag,
    )

    fun specifications(): List<AgentToolSpec> = operations.map { (name, operation) ->
        AgentToolSpec(
            name,
            "User's external Chromium browser: ${descriptions.getValue(operation)}",
            schema(operation),
            if (operation in commands) AgentToolAction.Command else AgentToolAction.Read,
        )
    }

    private val descriptions = mapOf(
        ExternalOperation.Tabs to
            "List your controlled tabs and identify the user's active tab; other user tabs are not " +
            "listed.",
        ExternalOperation.ActiveTab to
            "Read the user's active page without taking control. This returns text, not input " +
            "references.",
        ExternalOperation.NewTab to
            "Create your own tab in the Heartbeat group, optionally opening a public URL. For private " +
            "URLs, open a blank tab then use ex_control_open. Returns tabId.",
        ExternalOperation.CloseTab to
            "Close your agent-created tab. Return user tabs with release to preserve their unsaved " +
            "work.",
        ExternalOperation.RequestTab to
            "Acquire the user's active tab after authorization. Returns tabId for subsequent actions.",
        ExternalOperation.Release to
            "Give a controlled tab back to the user and leave it open.",
        ExternalOperation.Open to
            "Navigate a controlled tab to an HTTP(S) URL. Read the page after navigation.",
        ExternalOperation.Search to
            "Search the web in a controlled tab using Google. Read the results after navigation.",
        ExternalOperation.Back to
            "Go to the previous history entry of a controlled tab. Read again afterwards.",
        ExternalOperation.Forward to
            "Go to the next history entry of a controlled tab. Read again afterwards.",
        ExternalOperation.Reload to
            "Reload a controlled tab. Read again afterwards.",
        ExternalOperation.Activate to
            "Bring a controlled tab and its browser window to the front.",
        ExternalOperation.Read to
            "Read visible page text and interactive elements. Returns snapshotId and element " +
            "references.",
        ExternalOperation.Screenshot to
            "Capture the viewport as an image. Returns snapshotId and CSS viewport dimensions.",
        ExternalOperation.Click to
            "Click an element from the latest read or a CSS viewport point from the latest snapshot.",
        ExternalOperation.Type to
            "Insert text into the focused field. Click the intended field first; this does not " +
            "replace its value.",
        ExternalOperation.Key to
            "Press and release one supported key in the focused control. Enter may submit a form.",
        ExternalOperation.Scroll to
            "Scroll vertically at a CSS viewport point using the latest snapshotId. Read again " +
            "afterwards.",
        ExternalOperation.Drag to
            "Drag between CSS viewport points using the latest snapshotId. Read again afterwards.",
    )

    private fun schema(operation: ExternalOperation): JsonObject {
        val required = requiredFields[operation] ?: listOf("tabId")
        val optional = optionalFields[operation].orEmpty()
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject((required + optional).associateWith { properties.getValue(it) }))
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
            if (operation == ExternalOperation.Click) {
                put(
                    "anyOf",
                    Json.parseToJsonElement("""[{"required":["element"]},{"required":["x","y"]}]"""),
                )
            }
        }
    }

    private val requiredFields = mapOf(
        ExternalOperation.Tabs to emptyList(),
        ExternalOperation.ActiveTab to emptyList(),
        ExternalOperation.NewTab to emptyList(),
        ExternalOperation.RequestTab to emptyList(),
        ExternalOperation.Open to listOf("tabId", "url"),
        ExternalOperation.Search to listOf("tabId", "query"),
        ExternalOperation.Click to listOf("tabId", "snapshotId"),
        ExternalOperation.Type to listOf("tabId", "text"),
        ExternalOperation.Key to listOf("tabId", "key"),
        ExternalOperation.Scroll to listOf("tabId", "snapshotId", "x", "y", "deltaY"),
        ExternalOperation.Drag to listOf("tabId", "snapshotId", "x", "y", "toX", "toY"),
    )
    private val optionalFields = mapOf(
        ExternalOperation.NewTab to listOf("url"),
        ExternalOperation.Click to listOf("element", "x", "y"),
    )

    private val properties = Json.parseToJsonElement(
        """{
        "tabId":{"type":"string","minLength":1,"description":"Owned tabId from new_tab, request_tab or tabs."},
        "url":{"type":"string","minLength":1,"description":"HTTP(S) URL; bare hosts use HTTPS."},
        "query":{"type":"string","minLength":1,"description":"Web search terms."},
        "snapshotId":{"type":"string","minLength":1,"description":"Latest read/screenshot of this tab; refresh after actions."},
        "element":{"type":"integer","minimum":0,"maximum":199,"description":"Element id from the latest read."},
        "x":{"type":"number","minimum":0,"description":"Viewport X in CSS pixels."},
        "y":{"type":"number","minimum":0,"description":"Viewport Y in CSS pixels."},
        "toX":{"type":"number","minimum":0,"description":"Drag destination X in CSS pixels."},
        "toY":{"type":"number","minimum":0,"description":"Drag destination Y in CSS pixels."},
        "deltaY":{"type":"number","minimum":-10000,"maximum":10000,"description":"Vertical scroll; positive scrolls down."},
        "text":{"type":"string","maxLength":20000,"description":"Exact text inserted into the focused field."},
        "key":{"type":"string","enum":["Enter","Tab","Escape","Backspace","Delete","ArrowLeft","ArrowUp","ArrowRight","ArrowDown","Home","End"]}
        }""",
    ).jsonObject
}
