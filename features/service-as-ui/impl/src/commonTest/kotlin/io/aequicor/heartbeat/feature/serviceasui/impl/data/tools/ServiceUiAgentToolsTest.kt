package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiEditRejection
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwnerInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteResult
import io.aequicor.heartbeat.feature.serviceasui.api.apply
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ServiceUiAgentToolsTest {
    private val owner = UiOwner("harness", "h_1")
    private val machine = FakeMachine()
    private val toggles = Toggles()
    private val cards = Cards()
    private val tools = ServiceUiAgentTools(lazyOf(machine), lazyOf(Calls), lazyOf(cards), toggles, FixedClock)
    private val context = AgentToolContext(SessionRef(EngineId("pi"), SessionSourceId("s"), "chat"), null, TurnId("t1"))

    @Test
    fun `tools follow the toggle and the catalog names them all`() = runTest {
        assertEquals(
            listOf(
                ServiceUiTools.REFERENCE,
                ServiceUiTools.LIST,
                ServiceUiTools.GET,
                ServiceUiTools.PUT,
                ServiceUiTools.DELETE,
                ServiceUiTools.SHOW,
                ServiceUiTools.CALL,
            ),
            tools.catalog.map { it.name },
        )
        assertEquals(7, tools.specifications(null).size)
        assertTrue("ui_reference" in tools.instructions(null))
        assertTrue("Heartbeat interface spec" in call(ServiceUiTools.REFERENCE).text)

        toggles.isEnabled.value = false
        assertEquals(emptyList(), tools.specifications(null))
        assertEquals("", tools.instructions(null))
        assertTrue(call(ServiceUiTools.LIST).isError)
    }

    @Test
    fun `an approved put saves the interface which list and get show`() = runTest {
        val saved = call(ServiceUiTools.PUT, put(SPEC))
        assertFalse(saved.isError, saved.text)
        assertEquals("Saved interface harness:h_1:start at revision 1.", saved.text)

        val list = Json.parseToJsonElement(call(ServiceUiTools.LIST).text).jsonObject
        assertEquals(
            "harness:h_1:start",
            list.getValue("surfaces").jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content,
        )
        val owners = list.getValue("owners").jsonArray.single().jsonObject
        assertEquals("pr_map", owners["name"]?.jsonPrimitive?.content)
        assertEquals("prs", owners.getValue("methods").jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)

        val got = Json.parseToJsonElement(
            call(ServiceUiTools.GET, buildJsonObject { put("id", "harness:h_1:start") }).text,
        )
        assertEquals(Json.parseToJsonElement(SPEC), got.jsonObject["spec"])

        val again = call(ServiceUiTools.PUT, put(SPEC))
        assertTrue(again.isError)
        assertTrue("pass expectedRevision 1" in again.text, again.text)
        assertFalse(call(ServiceUiTools.PUT, put(SPEC, revision = 1)).isError)
    }

    @Test
    fun `an unreadable stored spec is returned for recovery without leaking it to logs`() = runTest {
        call(ServiceUiTools.PUT, put(SPEC))
        val ready = machine.state.value as ServiceUiState.Ready
        val privateText = "private-surface-content"
        val broken = "{\"text\":\"$privateText\""
        machine.state.value = ready.copy(
            library = ready.library.copy(
                surfaces = ready.library.surfaces.map { it.copy(content = it.content.copy(spec = broken)) },
            ),
        )
        val records = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val sink = LogSink { _, _, error, message ->
            records += message
            error?.let { errors += it }
        }
        Log.init(isDebug = false, sinks = listOf(sink))
        try {
            val result = call(ServiceUiTools.GET, buildJsonObject { put("id", "harness:h_1:start") })
            assertEquals(JsonPrimitive(broken), Json.parseToJsonElement(result.text).jsonObject["spec"])
            assertEquals(1, errors.size)
            assertTrue(records.any { "unreadable" in it })
            assertFalse(privateText in (records + errors.map { it.stackTraceToString() }).joinToString())
        } finally {
            Log.init(isDebug = false)
        }
    }

    @Test
    fun `an invalid spec an unknown owner or a stale approval change nothing`() = runTest {
        val invalid = call(
            ServiceUiTools.PUT,
            put("""{"version":1,"body":{"type":"button","label":"Go","action":{"type":"call","method":"nope"}}}"""),
        )
        assertTrue(invalid.isError)
        assertTrue("$.body.action.method" in invalid.text, invalid.text)

        val stranger = call(ServiceUiTools.PUT, put(SPEC, owner = "harness:h_9"))
        assertTrue("No owner harness:h_9" in stranger.text, stranger.text)

        val approved = approval(ServiceUiTools.PUT, put(SPEC))
        val changed = execute(ServiceUiTools.PUT, put("""{"version":1,"body":{"type":"divider"}}"""), approved)
        assertTrue("changed while awaiting approval" in changed.text, changed.text)
        assertEquals(0, machine.writes)
    }

    @Test
    fun `put identifies an invalid card gap and accepts it on a nested column`() = runTest {
        val invalid = """{"version":1,"body":{"children":[{"children":[],"gap":"small","type":"card"}],
            "type":"column","gap":"large"}}"""
        val rejected = call(ServiceUiTools.PUT, put(invalid))
        assertTrue(rejected.isError)
        assertTrue("$.body.children[0]:" in rejected.text, rejected.text)
        assertTrue("gap" in rejected.text, rejected.text)
        assertEquals(0, machine.writes)

        val valid = """{"version":1,"body":{"children":[{"children":[{"children":[],"gap":"small",
            "type":"column"}],"type":"card"}],"type":"column","gap":"large"}}"""
        val saved = call(ServiceUiTools.PUT, put(valid))
        assertFalse(saved.isError, saved.text)
        assertEquals(1, machine.writes)
    }

    @Test
    fun `a web document always asks and deletes need the current revision`() = runTest {
        val web = put("""{"version":1,"body":{"type":"web","html":"<p>Hi</p>"}}""", placement = "panel")
        val spec = tools.specifications(null).first { it.name == ServiceUiTools.PUT }
        assertTrue(tools.requiresDecision(context, spec, web))
        assertFalse(tools.requiresDecision(context, spec, put(SPEC)))

        call(ServiceUiTools.PUT, put(SPEC))
        val stale = call(ServiceUiTools.DELETE, delete(revision = 3))
        assertTrue("Not saved: the interface changed meanwhile" in stale.text, stale.text)
        assertEquals("Deleted interface harness:h_1:start.", call(ServiceUiTools.DELETE, delete(revision = 1)).text)
        assertEquals(emptyList(), (machine.state.value as ServiceUiState.Ready).library.surfaces)
    }

    @Test
    fun `cards show inline specs or library interfaces and stop when the chat is full`() = runTest {
        val inline = call(ServiceUiTools.SHOW, show(spec = """{"version":1,"body":{"type":"text","text":"Hi"}}"""))
        val id = ServiceUiTools.shownCardId(ServiceUiTools.SHOW, inline.text)
        assertEquals(context.session, cards.added.single { it.id == id }.chat)

        val calling = call(ServiceUiTools.SHOW, show(spec = SPEC))
        assertTrue(calling.isError)
        assertTrue("not allowed in a card without an owner" in calling.text, calling.text)
        assertTrue(call(ServiceUiTools.SHOW, show(surface = "harness:h_1:start")).isError)

        call(ServiceUiTools.PUT, put(SPEC))
        assertFalse(call(ServiceUiTools.SHOW, show(surface = "harness:h_1:start")).isError)
        val web = show(
            spec = """{"version":1,"modals":{"view":{"title":"V","body":{"type":"web","html":"<p>Hi</p>"}}},
                "body":{"type":"button","label":"Open","action":{"type":"open","modal":"view"}}}""",
        )
        val spec = ServiceUiToolSpecs.first { it.name == ServiceUiTools.SHOW }
        assertTrue(tools.requiresDecision(context, spec, web))

        cards.isFull = true
        assertTrue("already shows 64 cards" in call(ServiceUiTools.SHOW, show(surface = "harness:h_1:start")).text)
    }

    @Test
    fun `a method call returns what the owner's method returns`() = runTest {
        val result = call(
            ServiceUiTools.CALL,
            buildJsonObject {
                put("owner", "pr_map")
                put("method", "prs")
            },
        )
        assertEquals(AgentToolResult("1"), result)
        val stranger = call(
            ServiceUiTools.CALL,
            buildJsonObject {
                put("owner", "nobody")
                put("method", "prs")
            },
        )
        assertTrue(stranger.isError)
    }

    private fun show(spec: String? = null, surface: String? = null) = buildJsonObject {
        put("title", "PRs")
        spec?.let { put("spec", Json.parseToJsonElement(it)) }
        surface?.let { put("surface", it) }
    }

    private suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): AgentToolResult =
        execute(name, arguments, approval(name, arguments))

    private suspend fun approval(name: String, arguments: JsonObject) =
        tools.approval(context, tools.catalogSpec(name), arguments)

    private suspend fun execute(
        name: String,
        arguments: JsonObject,
        approval: io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval,
    ) = tools.execute(context.copy(authorization = approval), name, arguments)

    private fun ServiceUiAgentTools.catalogSpec(name: String) = ServiceUiToolSpecs.first { it.name == name }

    private fun put(spec: String, owner: String = "pr_map", placement: String = "start", revision: Long? = null) =
        buildJsonObject {
            put("owner", owner)
            put("name", "start")
            put("placement", placement)
            put("title", "PR map")
            put("spec", Json.parseToJsonElement(spec))
            revision?.let { put("expectedRevision", it) }
        }

    private fun delete(revision: Int) = buildJsonObject {
        put("id", "harness:h_1:start")
        put("expectedRevision", revision)
    }

    private inner class FakeMachine : Machine<ServiceUiState, ServiceUiIntent, ServiceUiOutput> {
        override val name = "service_ui"
        override val state = MutableStateFlow<ServiceUiState>(
            ServiceUiState.Ready(
                owners = mapOf("harness" to UiOwners(true, listOf(UiOwnerInfo(owner.id, "pr_map", "PR map", true)))),
            ),
        )
        override val outputs = MutableSharedFlow<ServiceUiOutput>(extraBufferCapacity = 8)
        var writes = 0

        override suspend fun send(intent: ServiceUiIntent): SendResult {
            val edit = intent as ServiceUiIntent.Internal.Edit
            writes++
            val ready = state.value as ServiceUiState.Ready
            outputs.tryEmit(
                when (val result = ready.library.apply(edit.write.op)) {
                    is UiWriteResult.Applied -> {
                        state.value = ready.copy(library = result.library)
                        ServiceUiOutput.EditApplied(edit.write.id, result.surface, result.removed)
                    }

                    is UiWriteResult.Rejected -> ServiceUiOutput.EditRejected(
                        edit.write.id,
                        UiEditRejection.valueOf(result.reason.name),
                    )
                },
            )
            return SendResult.Accepted
        }
    }

    private class Cards : UiCardStorage {
        val added = mutableListOf<UiCard>()
        var isFull = false

        override suspend fun add(card: UiCard): UiCardAdded = if (isFull) {
            UiCardAdded.ChatFull
        } else {
            added += card
            UiCardAdded.Stored(0)
        }

        override fun observe(id: String): Flow<UiCard?> = flowOf(added.firstOrNull { it.id == id })

        override suspend fun latest(chat: SessionRef, surface: UiSurfaceId): UiCard? =
            added.lastOrNull { it.chat == chat && (it.source as? UiCardSource.Surface)?.surface == surface }
    }

    private object Calls : UiMethodCalls {
        override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> = flowOf(emptySet())
        override fun methods(owner: UiOwner): Flow<UiMethods> =
            flowOf(UiMethods(isAvailable = true, listOf(UiMethodInfo("prs", "Open pull requests"))))

        override suspend fun call(
            owner: UiOwner,
            method: String,
            arguments: JsonObject,
            context: UiCallContext,
        ): UiMethodResult = UiMethodResult.Success(JsonPrimitive(1))
    }

    private class Toggles : FeatureToggles {
        val isEnabled = MutableStateFlow(true)

        @Suppress("UNCHECKED_CAST") // Tests use the one boolean flag of the feature.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = isEnabled as Flow<T>
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_000)
    }

    private companion object {
        const val SPEC = """{"version":1,"data":{"prs":{"method":"prs"}},"body":{"type":"column","children":[
            {"type":"text","text":"{{data.prs.count}} open"},
            {"type":"button","label":"Refresh","action":{"type":"reload","source":"prs"}}]}}"""
    }
}
