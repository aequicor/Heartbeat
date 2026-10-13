package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileBrowserDocumentsTest {
    private val documents = ProfileBrowserDocuments()

    @Test
    fun `an opened document is found by its route until it closes`() {
        val document = BrowserDocument("<p>Hi</p>", "Greeting", JsonPrimitive(1))
        val handle = assertNotNull(documents.open(document))
        val id = handle.route.id
        assertTrue(Regex("[0-9a-f]{32}").matches(id), id)
        val open = assertNotNull(documents.documents.value[id])
        assertSame(document, open.document)
        assertEquals(JsonPrimitive(1), open.data.value)

        handle.update(JsonPrimitive(2))
        assertEquals(JsonPrimitive(2), open.data.value)
        handle.close()
        handle.close()
        assertNull(documents.documents.value[id])
        handle.update(JsonPrimitive(3))
        assertEquals(JsonPrimitive(2), open.data.value)
    }

    @Test
    fun `the same HTML opened twice is two documents`() {
        val first = assertNotNull(documents.open(BrowserDocument("<p>A</p>", "A")))
        val second = assertNotNull(documents.open(BrowserDocument("<p>A</p>", "A")))
        assertNotEquals(first.route, second.route)
        first.close()
        assertNotNull(documents.documents.value[second.route.id])
    }

    @Test
    fun `oversized documents and data are refused`() {
        val html = "é".repeat(BrowserDocumentLimits.HTML_BYTES / 2 + 1)
        assertNull(documents.open(BrowserDocument(html, "Large")))
        val data = JsonPrimitive("x".repeat(BrowserDocumentLimits.DATA_BYTES))
        assertNull(documents.open(BrowserDocument("<p/>", "Large data", data)))

        val handle = assertNotNull(documents.open(BrowserDocument("<p/>", "Small", JsonPrimitive(1))))
        handle.update(data)
        assertEquals(JsonPrimitive(1), documents.documents.value.getValue(handle.route.id).data.value)
        assertEquals(1, documents.documents.value.size)
    }

    @Test
    fun `a profile holds a bounded number of open documents`() {
        val handles = List(BrowserDocumentLimits.DOCUMENTS) {
            assertNotNull(documents.open(BrowserDocument("<p/>", "$it")))
        }
        assertNull(documents.open(BrowserDocument("<p/>", "extra")))
        handles.first().close()
        assertNotNull(documents.open(BrowserDocument("<p/>", "replacement")))
    }
}
