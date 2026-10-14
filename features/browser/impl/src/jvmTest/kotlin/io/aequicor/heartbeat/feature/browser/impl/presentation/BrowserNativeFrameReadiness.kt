package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Gives a newly attached or mutated fixture a rendering opportunity before reading geometry and sending input.
 * The second animation frame follows the first frame's rendering steps, as in the raw JCEF visual probe.
 * Hidden or zero-sized documents fail instead of being activated; this does not retry input or assert that
 * compositor presentation has completed. Missing rendering callbacks remain bounded by the test timeout.
 */
internal suspend fun DesktopBrowserEngine.awaitNativeFixtureFrame() {
    val response = withTimeout(10_000) {
        devTools(
            "Runtime.evaluate",
            buildJsonObject {
                put(
                    "expression",
                    """
                    new Promise((resolve,reject)=>{
                        const visible=()=>document.visibilityState==='visible' && innerWidth>0 && innerHeight>0;
                        if (!visible()) { reject(new Error('Native fixture must be visible and sized')); return; }
                        requestAnimationFrame(()=>requestAnimationFrame(()=>{
                            if (!visible()) { reject(new Error('Native fixture became hidden or zero-sized')); return; }
                            resolve(true);
                        }));
                    })
                    """.trimIndent(),
                )
                put("returnByValue", true)
                put("awaitPromise", true)
            },
        )
    }
    val result = Json.parseToJsonElement(response).jsonObject
    check(result["exceptionDetails"] == null) { "Native fixture could not render: $response" }
    check(result.getValue("result").jsonObject["value"] == JsonPrimitive(true)) {
        "Native fixture did not acknowledge a rendering opportunity: $response"
    }
}
