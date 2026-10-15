package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentRoute
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentSizing
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.impl.data.UnsupportedUiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebConfiguration
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecDecoding
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.decodeUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiFacts
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.WebHeightUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.subscribe
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.testStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pro.respawn.flowmvi.api.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfaceWebDocumentsTest {
    @Test
    fun `web nodes get documents only while web documents are on named by their surface or modal`() = runTest {
        val fixture = Fixture(this)
        val screen = subscribe(fixture.model.store)
        assertTrue(fixture.web.opened.isEmpty())

        fixture.web.enabled.value = true
        runCurrent()
        val body = fixture.web.opened.single()
        assertEquals("Map", body.document.title)
        assertEquals("<p>A</p>", body.document.html)
        assertEquals(JsonPrimitive(1), body.document.data)
        assertEquals(mapOf(webKey(screen) to body.route.id), fixture.model.webDocuments.value)

        screen.intent(SurfaceIntent.Press(button(screen).press))
        runCurrent()
        assertEquals(listOf<JsonElement?>(JsonPrimitive(2)), body.updates)
        assertEquals(1, fixture.web.opened.size)

        fixture.web.enabled.value = false
        runCurrent()
        assertTrue(body.isClosed)
        assertTrue(fixture.model.webDocuments.value.isEmpty())
    }

    @Test
    fun `policy changes replace active documents with latest data and revoke old handles`() = runTest {
        val fixture = Fixture(this, enabled = true)
        val screen = subscribe(fixture.model.store)
        val isolated = fixture.web.opened.single()
        assertEquals(BrowserDocumentResourcePolicy.Isolated, isolated.document.resourcePolicy)
        screen.intent(SurfaceIntent.Press(button(screen).press))
        runCurrent()
        fixture.web.policy.value = BrowserDocumentResourcePolicy.ExternalAssets
        runCurrent()
        val external = fixture.web.opened.last()
        assertTrue(isolated.isClosed)
        assertEquals(JsonPrimitive(2), external.document.data)
        assertEquals(BrowserDocumentResourcePolicy.ExternalAssets, external.document.resourcePolicy)
        fixture.web.policy.value = BrowserDocumentResourcePolicy.Isolated
        runCurrent()
        assertTrue(external.isClosed)
        assertEquals(BrowserDocumentResourcePolicy.Isolated, fixture.web.opened.last().document.resourcePolicy)
        assertEquals(3, fixture.web.opened.size)
    }

    @Test
    fun `a modal's document closes with the modal and the surface closes the rest`() = runTest {
        val fixture = Fixture(this, enabled = true)
        val screen = subscribe(fixture.model.store)
        screen.intent(SurfaceIntent.Press(button(screen, index = 2).press))
        runCurrent()
        val modal = fixture.web.opened.last()
        assertEquals("Modal", modal.document.title)
        assertNull(modal.document.bridge, "Only nodes that ask for it get the bridge")

        screen.intent(SurfaceIntent.DismissModal)
        runCurrent()
        assertTrue(modal.isClosed)
        val body = fixture.web.opened.first()
        assertTrue(!body.isClosed)
        fixture.surface.cancel()
        runCurrent()
        assertTrue(body.isClosed)
    }

    @Test
    fun `the page calls the owner's methods with object arguments and reads their outcome`() = runTest {
        val fixture = Fixture(this, enabled = true)
        subscribe(fixture.model.store)
        val bridge = assertNotNull(fixture.web.opened.single().document.bridge)

        val value = bridge.call("prs", buildJsonObject { put("state", "open") })
        assertEquals(buildJsonObject { put("count", 2) }, assertIs<BrowserBridgeResult.Value>(value).value)
        assertEquals(listOf("prs" to buildJsonObject { put("state", "open") }), fixture.calls.made)
        bridge.call("prs", JsonNull)
        assertEquals(JsonObject(emptyMap()), fixture.calls.made.last().second)

        val notObject = bridge.call("prs", JsonPrimitive(1))
        assertEquals("Arguments must be a JSON object", assertIs<BrowserBridgeResult.Failure>(notObject).message)
        fixture.calls.next = UiMethodResult.UserError("No PRs")
        assertEquals("No PRs", (bridge.call("prs", JsonNull) as BrowserBridgeResult.Failure).message)
        fixture.calls.next = UiMethodResult.Failure(UiMethodFailure.NotFound)
        assertEquals("No such method", (bridge.call("other", JsonNull) as BrowserBridgeResult.Failure).message)
    }

    @Test
    fun `a refused document is asked for again only after its node changes or web documents turn on again`() = runTest {
        val fixture = Fixture(this, enabled = true, refuse = true)
        val screen = subscribe(fixture.model.store)
        assertEquals(1, fixture.web.requests)
        screen.intent(SurfaceIntent.Press(button(screen).press))
        runCurrent()
        assertEquals(1, fixture.web.requests, "A data change does not ask again")

        fixture.web.refuse = false
        fixture.web.enabled.value = false
        runCurrent()
        fixture.web.enabled.value = true
        runCurrent()
        assertEquals(2, fixture.web.requests)
        assertEquals(JsonPrimitive(2), fixture.web.opened.single().document.data)
    }

    @Test
    fun `turning web documents off and on opens a new document`() = runTest {
        val fixture = Fixture(this, enabled = true)
        subscribe(fixture.model.store)
        val first = fixture.web.opened.single()
        fixture.web.enabled.value = false
        runCurrent()
        fixture.web.enabled.value = true
        runCurrent()
        assertTrue(first.isClosed)
        val second = fixture.web.opened.last()
        assertTrue(first !== second && !second.isClosed)
        assertEquals(listOf(second.route.id), fixture.model.webDocuments.value.values.toList())
    }

    @Test
    fun `previews and surfaces without an owner get no bridge`() = runTest {
        listOf(Fixture(this, enabled = true, isPreview = true), Fixture(this, enabled = true, hasOwner = false))
            .forEach { fixture ->
                subscribe(fixture.model.store)
                assertNull(fixture.web.opened.single().document.bridge)
            }
    }

    @Test
    fun `an owned response card gets the bridge without opening another interface`() = runTest {
        val fixture = Fixture(this, enabled = true, isCard = true)
        subscribe(fixture.model.store)
        val document = fixture.web.opened.single()
        val bridge = assertNotNull(document.document.bridge)
        assertIs<BrowserBridgeResult.Value>(bridge.call("prs", JsonNull))
        assertEquals(1, fixture.calls.made.size)
        assertEquals(1, fixture.web.opened.size)
    }

    @Test
    fun `durable data refresh updates the existing card page without replacing its document`() = runTest {
        val fixture = Fixture(
            this,
            enabled = true,
            isCard = true,
            specText = """{"version":1,"data":{"snapshot":{"method":"prs"}},"body":{
                "type":"web","html":"<p>App</p>","bridge":true,"data":"{{data.snapshot}}"}}""",
        )
        subscribe(fixture.model.store)
        val document = fixture.web.opened.single()
        assertEquals(buildJsonObject { put("count", 2) }, document.updates.last())
        fixture.calls.next = UiMethodResult.Success(buildJsonObject { put("count", 7) })
        fixture.calls.revision.value = 1L
        runCurrent()
        assertEquals(buildJsonObject { put("count", 7) }, document.updates.last())
        assertEquals(1, fixture.web.opened.size)
        assertTrue(!document.isClosed)
    }

    @Test
    fun `script replacement updates the existing card document without a durable write`() = runTest {
        val fixture = Fixture(
            this,
            enabled = true,
            isCard = true,
            specText = """{"version":1,"data":{"snapshot":{"method":"prs"}},"body":{
                "type":"web","html":"<p>App</p>","bridge":true,"data":"{{data.snapshot}}"}}""",
        )
        subscribe(fixture.model.store)
        val document = fixture.web.opened.single()
        val updated = buildJsonObject { put("count", 9) }
        fixture.calls.next = UiMethodResult.Success(updated)
        fixture.calls.published.value = fixture.calls.published.value.copy(generation = "replacement")
        runCurrent()

        assertEquals(2, fixture.calls.made.size)
        assertEquals(0L, fixture.calls.revision.value)
        assertEquals(updated, document.updates.last())
        assertEquals(1, fixture.web.opened.size)
        assertTrue(!document.isClosed)
        assertNotNull(document.document.bridge)
    }

    @Test
    fun `content sizing is presentation only and changing its mode replaces the document`() {
        val web = FakeWeb(true)
        val documents = SurfaceWebDocuments(web, bridge = null, title = "App")
        val content = UiNodeUi.Web("app", "<p>App</p>", hasBridge = true, JsonNull, WebHeightUi.Content)
        documents.configure(UiWebConfiguration(isEnabled = true))
        documents.show(SurfaceState(root = content))
        val first = web.opened.single()
        assertEquals(BrowserDocumentSizing.Content, first.document.sizing)
        assertNull(first.document.bridge, "Content measurement must not create method authority")

        documents.show(SurfaceState(root = content.copy(data = JsonPrimitive(1))))
        assertEquals(1, web.opened.size)
        assertEquals(listOf<JsonElement?>(JsonPrimitive(1)), first.updates)
        documents.show(SurfaceState(root = content.copy(height = WebHeightUi.Regular)))
        assertTrue(first.isClosed)
        assertEquals(BrowserDocumentSizing.Fixed, web.opened.last().document.sizing)
        documents.close()
        assertTrue(web.opened.last().isClosed)
    }

    private fun column(screen: SurfaceProviderOf) = assertIs<UiNodeUi.Column>(screen.states.value.root)

    private fun webKey(screen: SurfaceProviderOf) = assertIs<UiNodeUi.Web>(column(screen).children[0]).key

    private fun button(screen: SurfaceProviderOf, index: Int = 1) =
        assertIs<UiNodeUi.Button>(column(screen).children[index])

    private class Fixture(
        scope: TestScope,
        enabled: Boolean = false,
        hasOwner: Boolean = true,
        isCard: Boolean = false,
        isPreview: Boolean = false,
        refuse: Boolean = false,
        specText: String = SPEC,
    ) {
        val web = FakeWeb(enabled).also { it.refuse = refuse }
        val calls = Calls()
        val surface = CoroutineScope(scope.backgroundScope.coroutineContext + Job())
        val model = SurfaceModel(
            SurfaceSession(
                (decodeUiSpec(specText) as UiSpecDecoding.Decoded).spec,
                UiOwner("harness", "h_1").takeIf { hasOwner },
                UiCallContext(),
                JsonObject(emptyMap()),
                UiFacts(),
                NoHost,
                isCard,
                isPreview = isPreview,
            ),
            calls,
            UnsupportedUiPlatform,
            testStores(scope),
            surface,
            web,
        )
    }

    private class FakeWeb(enabled: Boolean) : UiWebDocuments {
        val enabled = MutableStateFlow(enabled)
        val opened = mutableListOf<Handle>()
        var refuse = false
        var requests = 0
        val policy = MutableStateFlow(BrowserDocumentResourcePolicy.Isolated)
        override val configuration: Flow<UiWebConfiguration> = combine(this.enabled, policy, ::UiWebConfiguration)

        override fun open(document: BrowserDocument): BrowserDocumentHandle? {
            requests++
            if (refuse) return null
            return Handle(document, BrowserDocumentRoute("doc-${opened.size}")).also { opened += it }
        }
    }

    private class Handle(val document: BrowserDocument, override val route: BrowserDocumentRoute) :
        BrowserDocumentHandle {
        val updates = mutableListOf<JsonElement?>()
        var isClosed = false

        override fun update(data: JsonElement?) {
            updates += data
        }

        override fun close() {
            isClosed = true
        }
    }

    private class Calls : UiMethodCalls {
        val revision = MutableStateFlow(0L)
        val published = MutableStateFlow(UiMethods(true, listOf(UiMethodInfo("prs", "PRs"))))
        val made = mutableListOf<Pair<String, JsonObject>>()
        var next: UiMethodResult = UiMethodResult.Success(buildJsonObject { put("count", 2) })

        override fun dataRevision(owner: UiOwner): Flow<Long> = revision

        override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> = flowOf(emptySet())

        override fun methods(owner: UiOwner): Flow<UiMethods> = published

        override suspend fun call(
            owner: UiOwner,
            method: String,
            arguments: JsonObject,
            context: UiCallContext,
        ): UiMethodResult {
            made += method to arguments
            return next
        }
    }

    private object NoHost : SurfaceHost {
        override suspend fun prompt(text: String, isSubmitted: Boolean) = false

        override suspend fun openPanel(name: String, args: JsonObject) = false

        override suspend fun openSurface(name: String, args: JsonObject) = false
    }

    private companion object {
        val SPEC = """
            {"version":1,"title":"Map","state":{"n":1},
             "modals":{"view":{"title":"Modal","body":{"type":"web","html":"<p>M</p>"}}},
             "body":{"type":"column","children":[
               {"type":"web","html":"<p>A</p>","bridge":true,"data":"{{state.n}}"},
               {"type":"button","label":"Next","action":{"type":"set","key":"n","value":2}},
               {"type":"button","label":"View","action":{"type":"open","modal":"view"}}
             ]}}
        """.trimIndent()
    }
}

private typealias SurfaceProviderOf = Provider<SurfaceState, SurfaceIntent, SurfaceAction>
