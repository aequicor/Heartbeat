package io.aequicor.heartbeat.feature.browser.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserDocumentsTest {
    @Test
    fun `documents and bridge results never print their content`() {
        val secret = "secret-value"
        val document = BrowserDocument(
            html = "<p>$secret</p>",
            title = secret,
            data = buildJsonObject { put("token", secret) },
            bridge = { _, _ -> BrowserBridgeResult.Value(JsonPrimitive(secret)) },
        )
        val printed = listOf(
            document.toString(),
            BrowserBridgeResult.Value(JsonPrimitive(secret)).toString(),
            BrowserBridgeResult.Failure(secret).toString(),
        )
        printed.forEach { assertFalse(secret in it, it) }
        assertTrue("bridge=true" in document.toString())
    }

    @Test
    fun `a document route keeps its id and its stable serial name`() {
        val route = BrowserDocumentRoute("0123456789abcdef0123456789abcdef")
        val json = Json.encodeToString(BrowserDocumentRoute.serializer(), route)
        assertEquals("""{"id":"0123456789abcdef0123456789abcdef"}""", json)
        assertEquals("browser_document", BrowserDocumentRoute.serializer().descriptor.serialName)
        assertEquals(route, Json.decodeFromString(BrowserDocumentRoute.serializer(), json))
    }
}
