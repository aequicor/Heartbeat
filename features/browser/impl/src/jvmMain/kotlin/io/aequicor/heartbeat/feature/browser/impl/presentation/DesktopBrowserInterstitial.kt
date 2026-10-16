package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Uses Chromium's own overridable certificate interstitial; never calls CefCallback.Continue. CEF does not expose
 * HSTS/strict_enforcement to Java and even supplies a callback for HSTS failures. The native error document owns
 * the proceed link only when Chrome permits that choice. A missing link, unknown document or navigation race fails
 * closed. The isolated execution context pins the operation to that document and excludes page-script overrides.
 */
internal class DesktopBrowserInterstitial(private val transport: suspend (String, JsonObject) -> String) {
    private var observed: Pair<String, String>? = null

    suspend fun isProceedAllowed(url: String, failureId: String = url): Boolean =
        inspect(url, false, observedId = failureId)

    suspend fun proceed(url: String, failureId: String = url, authorize: suspend () -> Boolean = { true }): Boolean {
        val document = observed?.takeIf { it.first == failureId } ?: return false
        return inspect(url, true, authorize, expectedLoader = document.second)
    }

    private suspend fun inspect(
        url: String,
        proceed: Boolean,
        authorize: suspend () -> Boolean = { true },
        observedId: String? = null,
        expectedLoader: String? = null,
    ): Boolean {
        val frame = frame()
        if (!frame.matches(url)) return false
        val loader = frame.getValue("loaderId").jsonPrimitive.content
        if (expectedLoader != null && loader != expectedLoader) return false
        val world = call(
            "Page.createIsolatedWorld",
            buildJsonObject {
                put("frameId", frame.getValue("id"))
                put("worldName", "heartbeat-certificate-" + Uuid.random())
            },
        )["executionContextId"] ?: return false
        val current = frame()
        if (!current.matches(url) || current["loaderId"] != frame["loaderId"]) return false
        if (!authorize()) return false
        val result = call(
            "Runtime.evaluate",
            buildJsonObject {
                put("contextId", world)
                put("returnByValue", true)
                put(
                    "expression",
                    """
                    (() => {
                      const link = document.getElementById('proceed-link');
                      if (!link || !document.getElementById('error-code')) return false;
                      if ($proceed) link.click();
                      return true;
                    })()
                    """.trimIndent(),
                )
            },
        )
        val isAllowed = result["exceptionDetails"] == null &&
            result["result"]?.jsonObject?.get("value")?.jsonPrimitive?.booleanOrNull == true
        if (isAllowed && observedId != null) observed = observedId to loader
        return isAllowed
    }

    private fun JsonObject.matches(url: String): Boolean =
        get("url")?.jsonPrimitive?.contentOrNull == "chrome-error://chromewebdata/" &&
            get("unreachableUrl")?.jsonPrimitive?.contentOrNull == url && get("loaderId") != null

    private suspend fun frame(): JsonObject = call("Page.getFrameTree", JsonObject(emptyMap()))
        .getValue("frameTree").jsonObject.getValue("frame").jsonObject

    private suspend fun call(method: String, args: JsonObject): JsonObject =
        Json.parseToJsonElement(transport(method, args)).jsonObject
}
