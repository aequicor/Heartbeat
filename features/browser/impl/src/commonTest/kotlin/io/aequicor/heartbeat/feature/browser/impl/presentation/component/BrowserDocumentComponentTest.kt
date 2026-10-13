package io.aequicor.heartbeat.feature.browser.impl.presentation.component

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class BrowserDocumentComponentTest {
    private val documents = ProfileBrowserDocuments()

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
