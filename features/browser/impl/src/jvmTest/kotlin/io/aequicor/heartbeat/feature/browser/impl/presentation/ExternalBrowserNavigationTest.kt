package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ExternalBrowserNavigationTest {
    @Test
    fun `history chooses adjacent entries and stops at either boundary`() = runTest {
        val driver = Driver()
        assertEquals(JsonPrimitive(10), driver.navigation.target(-1)?.get("id"))
        assertEquals(JsonPrimitive(30), driver.navigation.target(1)?.get("id"))
        driver.index = 0
        assertNull(driver.navigation.target(-1))
        assertEquals(JsonPrimitive(false), driver.navigation.history(-1) { true }["navigated"])
        driver.index = 2
        assertNull(driver.navigation.target(1))
        assertEquals(emptyList(), driver.mutations)
    }

    @Test
    fun `history checks the retained guard even for a cached destination`() = runTest {
        val driver = Driver()
        val inspected = mutableListOf<String>()
        assertFailsWith<IllegalArgumentException> {
            driver.navigation.history(-1) {
                inspected += it
                false
            }
        }
        assertEquals(listOf("http://private.local/"), inspected)
        assertEquals(emptyList(), driver.mutations)
    }

    @Test
    fun `history returns the committed page after the guarded navigation`() = runTest {
        val driver = Driver()
        val result = driver.navigation.history(1) { it == "https://next.example/" }
        assertEquals(JsonPrimitive(true), result["navigated"])
        assertEquals(JsonPrimitive("https://next.example/"), result["url"])
        assertEquals(JsonPrimitive("Next"), result["title"])
        assertEquals(listOf("Page.navigateToHistoryEntry" to json("""{"entryId":30}""")), driver.mutations)
    }

    @Test
    fun `history changed after review is refused before dispatch`() = runTest {
        val driver = Driver()
        val expected = driver.navigation.target(-1)
        driver.index = 2
        assertFailsWith<IllegalStateException> {
            driver.navigation.history(-1, expected) { true }
        }
        assertEquals(emptyList(), driver.mutations)
    }

    @Test
    fun `reload captures the form submission transition and refuses a changed entry`() = runTest {
        val driver = Driver()
        val expected = driver.navigation.currentEntry()
        assertEquals(JsonPrimitive("form_submit"), expected["transitionType"])
        driver.index = 2
        assertFailsWith<IllegalStateException> { driver.navigation.reload(expected) }
        assertEquals(emptyList(), driver.mutations)
    }

    @Test
    fun `reload sends no script or bypass parameters and reports the current page`() = runTest {
        val driver = Driver()
        val result = driver.navigation.reload()
        assertEquals(JsonPrimitive(true), result["reloaded"])
        assertEquals(JsonPrimitive("https://current.example/"), result["url"])
        assertEquals(listOf("Page.reload" to JsonObject(emptyMap())), driver.mutations)
    }

    private class Driver {
        var index = 1
        val mutations = mutableListOf<Pair<String, JsonObject>>()
        val navigation = ExternalBrowserNavigation { method, arguments ->
            if (method == "Page.getNavigationHistory") {
                """{"currentIndex":$index,"entries":[
                    {"id":10,"url":"http://private.local/","title":"Private"},
                    {"id":20,"url":"https://current.example/","title":"Current","transitionType":"form_submit"},
                    {"id":30,"url":"https://next.example/","title":"Next"}
                ]}
                """.trimIndent()
            } else {
                mutations += method to arguments
                if (method == "Page.navigateToHistoryEntry") index = 2
                "{}"
            }
        }
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
