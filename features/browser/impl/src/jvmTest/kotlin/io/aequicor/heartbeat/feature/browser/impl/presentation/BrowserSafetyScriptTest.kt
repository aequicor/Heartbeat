package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** The evidence script must describe the target the input driver reaches, not the one its own parsing would find. */
class BrowserSafetyScriptTest {
    @Test
    fun `numbers sent as strings are read as the driver reads them`() {
        val parsed = parsedSafetyArguments(
            buildJsonObject {
                put("element", "3")
                put("x", "100.5")
                put("y", 20)
                put("toX", "7")
                put("toY", JsonNull)
            },
        )
        assertEquals(
            buildJsonObject {
                put("element", 3)
                put("x", 100.5)
                put("y", 20.0)
                put("toX", 7.0)
            },
            parsed,
        )
    }

    @Test
    fun `values the driver rejects and text the evidence never needs are dropped`() {
        val parsed = parsedSafetyArguments(
            buildJsonObject {
                put("element", "3.5")
                put("x", "left")
                put("y", buildJsonObject { put("nested", 1) })
                put("text", "typed secret")
                put("snapshotId", JsonPrimitive("s1"))
            },
        )
        assertEquals(buildJsonObject {}, parsed)
    }
}
