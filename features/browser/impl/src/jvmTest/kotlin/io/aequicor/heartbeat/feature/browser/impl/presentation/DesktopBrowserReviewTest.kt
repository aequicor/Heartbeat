package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserReview
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserSessionTest.Fixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An agent action reviewed by the independent safety review runs only on the page and focus the review saw. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserReviewTest {
    @Test
    fun `inspection observes the page without touching it`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        val inspected = page.fixture.inspect(tab)
        assertNull(inspected.error)
        assertEquals(tab, inspected.data.getValue("tabId").jsonPrimitive.content)
        assertEquals("https://example.com/form", inspected.data.getValue("url").jsonPrimitive.content)
        val focus = inspected.data.getValue("page").jsonObject.getValue("focus").jsonObject
        assertEquals("search-box", focus.string("node"))
        assertTrue(page.methods.none { it.startsWith("Input.") })
    }

    @Test
    fun `inspection tells that an agent tab runs in the agent's own storage`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        assertEquals("agent", page.fixture.inspect(tab).data.getValue("storage").jsonPrimitive.content)
    }

    @Test
    fun `inspection tells that a handed over tab still runs in the user's storage`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page, owner = null)
        val pending = page.fixture.request(BrowserOperation.RequestControl, tab, AGENT)
        val approval = page.fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = pending.requestId)
        assertNull(approval.error)
        assertEquals("user", page.fixture.inspect(tab).data.getValue("storage").jsonPrimitive.content)
    }

    @Test
    fun `only the owning agent session can inspect its tab`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        assertNotNull(page.fixture.inspect(tab, owner = "other-session").error)
        assertNotNull(page.fixture.inspect(tab, owner = null).error)
        assertNotNull(page.fixture.inspect(tab, review = null).error)
        assertNotNull(page.fixture.inspect("missing").error)
        assertTrue(page.methods.none { it == "Runtime.evaluate" })
    }

    @Test
    fun `an action on the reviewed page and focus runs exactly once`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        val approved = page.fixture.inspect(tab).data
        val result = page.fixture.act(tab, BrowserOperation.Type, approved)
        assertNull(result.error)
        assertEquals(1, page.inserted.size)
        assertEquals("draft", page.inserted.single())
    }

    @Test
    fun `a focus change after the review refuses the action before any input`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        val approved = page.fixture.inspect(tab).data
        page.focus = "card-number"
        val result = page.fixture.act(tab, BrowserOperation.Type, approved)
        assertEquals("The browser action changed after authorization; read the page and request it again", result.error)
        assertTrue(page.methods.none { it.startsWith("Input.") })
    }

    @Test
    fun `a navigation or another document after the review refuses the action`() = runTest {
        val changes = listOf<(ReviewedPage) -> Unit>(
            { it.url = "https://example.com/pay" },
            { it.document = "next" },
        )
        for (change in changes) {
            val page = ReviewedPage()
            val tab = openAgentTab(page)
            val approved = page.fixture.inspect(tab).data
            change(page)
            assertNotNull(page.fixture.act(tab, BrowserOperation.Key, approved).error)
            assertTrue(page.methods.none { it.startsWith("Input.") })
        }
    }

    @Test
    fun `text the page rewrites after the review does not invalidate it`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        val approved = page.fixture.inspect(tab).data
        page.content = "Updated 12:01"
        page.title = "Search (1)"
        assertNull(page.fixture.act(tab, BrowserOperation.Type, approved).error)
        assertEquals(1, page.inserted.size)
    }

    @Test
    fun `evidence of a reviewed action cannot be replayed by a user request`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        val approved = page.fixture.inspect(tab).data
        val result = page.fixture.act(tab, BrowserOperation.Type, approved, owner = null)
        assertNotNull(result.error)
        assertTrue(page.methods.none { it.startsWith("Input.") })
    }

    @Test
    fun `an approval of another operation or without a state refuses the action before any input`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        val approved = page.fixture.inspect(tab).data
        val otherOperation = BrowserReview(BrowserOperation.Key, approved)
        val withoutState = BrowserReview(BrowserOperation.Type)
        for ((id, review) in listOf("other-operation" to otherOperation, "without-state" to withoutState)) {
            val result = page.fixture.session.perform(
                BrowserRequest(id, BrowserOperation.Type, tab, AGENT, textArguments(), review),
            )
            assertNotNull(result.error)
        }
        assertTrue(page.methods.none { it.startsWith("Input.") })
    }

    @Test
    fun `a navigation review observes no page, so a tab that cannot be evaluated can still be reviewed`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        val inspected = page.fixture.inspect(tab, review = BrowserReview(BrowserOperation.Navigate))
        assertNull(inspected.error)
        assertNull(inspected.data["page"])
        assertEquals("agent", inspected.data.getValue("storage").jsonPrimitive.content)
        assertTrue(page.methods.none { it == "Runtime.evaluate" })
    }

    @Test
    fun `inspection selects the inspected tab like any operation, as only a shown tab is read`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        val other = page.fixture.create(null)
        page.fixture.session.selectTab(other)
        assertNull(page.fixture.inspect(tab).error)
        assertEquals(tab, page.fixture.session.tabs.value.second)
    }

    @Test
    fun `an action without review evidence keeps its ordinary behaviour`() = runTest {
        val page = ReviewedPage()
        val tab = openAgentTab(page)
        assertNull(page.fixture.act(tab, BrowserOperation.Type, approved = null).error)
        assertEquals(1, page.inserted.size)
        assertTrue(page.methods.none { it == "Runtime.evaluate" })
    }

    private suspend fun TestScope.openAgentTab(page: ReviewedPage, owner: String? = AGENT): String {
        val tab = page.open(this).create(owner)
        page.fixture.attach()
        runCurrent()
        page.fixture.emit(BrowserViewState(url = page.url))
        runCurrent()
        return tab
    }

    private suspend fun Fixture.inspect(
        tab: String,
        owner: String? = AGENT,
        review: BrowserReview? = BrowserReview(BrowserOperation.Type),
    ): BrowserResult = session.perform(
        BrowserRequest("inspect-$tab-$owner", BrowserOperation.Inspect, tab, owner, textArguments(), review),
    )

    private suspend fun Fixture.act(
        tab: String,
        operation: BrowserOperation,
        approved: JsonObject?,
        owner: String? = AGENT,
    ): BrowserResult = session.perform(
        BrowserRequest(
            "act-$tab-$operation-$owner",
            operation,
            tab,
            owner,
            textArguments(),
            approved?.let { BrowserReview(operation, it) },
        ),
    )

    private fun textArguments() = buildJsonObject {
        put("text", "draft")
        put("key", "Enter")
    }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    /** A page that answers the DevTools calls of an inspection and records the input it receives. */
    private class ReviewedPage {
        lateinit var fixture: Fixture
        val methods = mutableListOf<String>()
        val inserted = mutableListOf<String>()
        var url = "https://example.com/form"
        var document = "doc-1"
        var focus = "search-box"
        var content = "Search the catalog"
        var title = "Search"

        fun open(scope: TestScope): Fixture = Fixture(scope, devTools = ::devTools, areFaviconsEnabled = false)
            .also { fixture = it }

        private fun devTools(method: String, params: JsonObject): String {
            methods += method
            return when (method) {
                "Page.getFrameTree" -> """{"frameTree":{"frame":{"id":"frame"}}}"""

                "Page.createIsolatedWorld" -> """{"executionContextId":7}"""

                "Runtime.evaluate" -> buildJsonObject {
                    put("result", buildJsonObject { put("value", evaluate(params)) })
                }.toString()

                "Input.insertText" -> "{}".also { inserted += params.getValue("text").jsonPrimitive.content }

                else -> "{}"
            }
        }

        private fun evaluate(params: JsonObject): JsonObject {
            val script = params.getValue("expression").jsonPrimitive.content
            return if ("heartbeatSafetySnapshot" in script) {
                buildJsonObject {
                    put("view", geometry())
                    put("title", title)
                    put("content", content)
                    put("isContentTruncated", false)
                    put("target", JsonPrimitive(null as String?))
                    put("focus", buildJsonObject { put("node", focus) })
                }
            } else {
                geometry()
            }
        }

        private fun geometry() = buildJsonObject {
            put("document", document)
            put("url", url)
            put("width", 800)
            put("height", 600)
            put("x", 0)
            put("y", 0)
        }
    }

    private companion object {
        const val AGENT = "agent-session"
    }
}
