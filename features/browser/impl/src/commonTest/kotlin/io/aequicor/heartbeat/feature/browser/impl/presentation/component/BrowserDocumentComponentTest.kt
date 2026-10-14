package io.aequicor.heartbeat.feature.browser.impl.presentation.component

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPresentation
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentSizing
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserDocumentComponentTest {
    private val documents = ProfileBrowserDocuments()

    @Test
    fun `fill keeps an intrinsically sized registration while overriding only its host layout`() =
        runTest(UnconfinedTestDispatcher()) {
            val handle = assertNotNull(
                documents.open(BrowserDocument("<p/>", "Page", sizing = BrowserDocumentSizing.Content)),
            )
            fun component(presentation: BrowserDocumentPresentation) = BrowserDocumentComponent(
                DefaultComponentContext(LifecycleRegistry()),
                handle.route.id,
                documents,
                backgroundScope,
                presentation = presentation,
            )
            val original = assertNotNull(component(BrowserDocumentPresentation.Document).surface.value)
            val fill = assertNotNull(component(BrowserDocumentPresentation.Fill).surface.value)
            assertSame(original.document, fill.document)
            assertTrue(original.isContentSized)
            assertFalse(fill.isContentSized)
            assertTrue(fill.isFill)
            assertEquals(1, documents.documents.value.size)
        }

    @Test
    fun `a route draws its document while open and nothing after it closes`() = runTest(UnconfinedTestDispatcher()) {
        val handle = assertNotNull(documents.open(BrowserDocument("<p/>", "Page")))
        val component = BrowserDocumentComponent(
            DefaultComponentContext(LifecycleRegistry()),
            handle.route.id,
            documents,
            backgroundScope,
        )
        val shown = assertNotNull(component.surface.value)
        assertEquals("Page", shown.title)
        handle.update(JsonPrimitive(1))
        assertSame(shown, component.surface.value)
        handle.close()
        assertNull(component.surface.value)

        val unknown = BrowserDocumentComponent(
            DefaultComponentContext(LifecycleRegistry()),
            "missing",
            documents,
            backgroundScope,
        )
        assertNull(unknown.surface.value)
    }
}
