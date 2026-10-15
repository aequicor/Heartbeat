package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Fixed history and reload commands over the external browser's guarded DevTools transport. History targets are
 * checked before dispatch as Chromium can restore them from its back-forward cache without a network request.
 * Only explicit HTTP(S) entries are eligible. The caller keeps its current navigation guard; revisiting an
 * address never grants a new origin permission.
 */
internal class ExternalBrowserNavigation(private val transport: suspend (String, JsonObject) -> String) {
    /** The exact history entry a review or navigation would target; no entry means the history boundary. */
    suspend fun target(offset: Int): JsonObject? {
        require(offset == -1 || offset == 1) { "History offset must be back or forward" }
        val history = call("Page.getNavigationHistory")
        val index = requireNotNull(history["currentIndex"]?.jsonPrimitive?.intOrNull) { "History unavailable" }
        val entries = history["entries"] as? JsonArray ?: error("History unavailable")
        return entries.getOrNull(index + offset)?.jsonObject
    }

    /** Navigation waits for completion in the extension and returns the resulting page, including redirects. */
    suspend fun history(offset: Int, expected: JsonElement? = null, isAllowed: (String) -> Boolean): JsonObject {
        val destination = target(offset)
        check(expected == null || expected == (destination ?: JsonNull)) {
            "History changed after safety review; request it again"
        }
        destination ?: return buildJsonObject { put("navigated", false) }
        val url = requireNotNull(destination["url"]?.jsonPrimitive?.content) { "History address unavailable" }
        require(isBrowserUrlAllowed(url)) { "History navigation supports only HTTP(S) pages" }
        require(isAllowed(url)) { "History address is blocked; open it explicitly to request access" }
        val id = requireNotNull(destination["id"]?.jsonPrimitive?.intOrNull) { "History entry unavailable" }
        call("Page.navigateToHistoryEntry", buildJsonObject { put("entryId", id) })
        return current("navigated")
    }

    /** Reloads only the reviewed history entry, preserving its transition type and existing document guard. */
    suspend fun reload(expected: JsonObject? = null): JsonObject {
        check(
            expected == null || expected == currentEntry(),
        ) { "History changed after safety review; request it again" }
        call("Page.reload")
        return current("reloaded")
    }

    /** Includes Chromium's transitionType so a reviewer can identify a possible form resubmission on reload. */
    suspend fun currentEntry(): JsonObject {
        val history = call("Page.getNavigationHistory")
        val index = requireNotNull(history["currentIndex"]?.jsonPrimitive?.intOrNull) { "History unavailable" }
        return (history["entries"] as? JsonArray)?.getOrNull(index)?.jsonObject
            ?: error("Current history entry unavailable")
    }

    private suspend fun current(action: String): JsonObject {
        val entry = currentEntry()
        return buildJsonObject {
            put(action, true)
            put("url", requireNotNull(entry["url"]) { "History address unavailable" })
            put("title", entry["title"]?.jsonPrimitive?.content.orEmpty())
        }
    }

    private suspend fun call(method: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
        Json.parseToJsonElement(transport(method, arguments)).jsonObject
}
