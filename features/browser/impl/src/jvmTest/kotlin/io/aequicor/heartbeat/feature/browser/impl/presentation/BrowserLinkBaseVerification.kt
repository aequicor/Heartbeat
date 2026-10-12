package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Exercises the real isolated-world scripts when only a relative link's resolved destination changes. */
internal suspend fun verifyChangedLinkBase(engine: DesktopBrowserEngine) {
    suspend fun evaluate(expression: String) = Json.parseToJsonElement(
        engine.devTools(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
            },
        ),
    ).jsonObject.getValue("result").jsonObject.getValue("value").jsonPrimitive.content

    evaluate(
        """(() => {
            document.head.innerHTML='<base href="https://before.example/">';
            document.body.innerHTML='<a href="action" style="position:fixed;left:20px;top:20px">Open</a>';
            window.linkClicks=0;
            document.querySelector('a').onclick=e=>{e.preventDefault();window.linkClicks++;};
            return true;
        })()""",
    )
    val driver = DesktopBrowserAutomation(engine::devTools)
    suspend fun read() = driver.perform(BrowserRequest("read-link", BrowserOperation.Read, "tab")).data
    suspend fun click(snapshot: JsonObject) = driver.perform(
        BrowserRequest(
            "click-link",
            BrowserOperation.Click,
            "tab",
            arguments = buildJsonObject {
                put("snapshotId", snapshot.getValue("snapshotId"))
                put("element", 0)
            },
        ),
    )
    val before = read()
    assertEquals(
        "https://before.example/action",
        before.getValue("elements").jsonArray.single().jsonObject.getValue("href").jsonPrimitive.content,
    )
    evaluate("document.querySelector('base').href='https://after.example/'; true")
    val failure = assertFailsWith<IllegalArgumentException> { click(before) }
    assertEquals("Element changed; read the page again", failure.message)
    assertEquals("0", evaluate("window.linkClicks"))
    val after = read()
    assertEquals(
        "https://after.example/action",
        after.getValue("elements").jsonArray.single().jsonObject.getValue("href").jsonPrimitive.content,
    )
    click(after)
    assertEquals("1", evaluate("window.linkClicks"))
}
