package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiEditRejection
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodReview
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwnerInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiWrite
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteResult
import io.aequicor.heartbeat.feature.serviceasui.api.apply
import io.aequicor.heartbeat.feature.serviceasui.impl.di.BundledUiAuthoringKnowledge
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ServiceUiAgentToolsTest {
    private val owner = UiOwner("harness", "h_1")
    private val machine = FakeMachine()
    private val toggles = Toggles()
    private val cards = Cards()
    private val calls = Calls()
    private val tools = ServiceUiAgentTools(
        lazyOf(machine),
        lazyOf(calls),
        lazyOf(cards),
        toggles,
        FixedClock,
        BundledUiAuthoringKnowledge(),
    )
    private val context = AgentToolContext(
        SessionRef(EngineId("pi"), SessionSourceId("s"), "chat"),
        null,
        TurnId("t1"),
        target = EngineTarget(EngineId("pi"), EngineBindingId("provider"), ModelId("model")),
    )

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
        assertEquals(AgentToolAction.Command, tools.catalogSpec(ServiceUiTools.SHOW).action)
        assertEquals(AgentToolAction.Read, tools.catalogSpec(ServiceUiTools.GET).action)
        assertEquals(AgentToolAction.Read, tools.catalogSpec(ServiceUiTools.LIST).action)
        assertTrue("ui_reference" in tools.instructions(null))
        assertTrue("Heartbeat interface spec" in call(ServiceUiTools.REFERENCE).text)

        toggles.isEnabled.value = false
        assertEquals(emptyList(), tools.specifications(null))
        assertEquals("", tools.instructions(null))
        assertTrue(call(ServiceUiTools.LIST).isError)
    }

    @Test
    fun `reference routes every bundled document and gives legacy callers all resources`() = runTest {
        val spec = call(ServiceUiTools.REFERENCE, referenceTopic("spec"))
        val all = call(ServiceUiTools.REFERENCE)
        val resources = mapOf(
            "mini-app-design" to "mini-app-design/SKILL.md",
            "service-as-ui" to "service-as-ui/SKILL.md",
            "service-as-ui/layout" to "service-as-ui/references/layout.md",
            "service-as-ui/actions-data" to "service-as-ui/references/actions-data.md",
            "service-as-ui/webview" to "service-as-ui/references/webview.md",
            "service-as-ui/visual-patterns" to "service-as-ui/references/visual-patterns.md",
            "service-as-ui/examples" to "service-as-ui/references/examples.md",
        )
        val documents = resources.map { (topic, path) ->
            val bundled = Res.readBytes("files/skills/$path").decodeToString()
            val result = call(ServiceUiTools.REFERENCE, referenceTopic(topic))
            assertFalse(result.isError, result.text)
            assertTrue(bundled.isNotBlank(), path)
            assertEquals(bundled, result.text, topic)
            assertFalse(bundled in spec.text)
            bundled
        }
        assertFalse(spec.isError, spec.text)
        assertFalse(all.isError, all.text)
        assertTrue(spec.text.isNotBlank())
        assertEquals((listOf(spec.text) + documents).joinToString("\n\n"), all.text)
        assertEquals(0, machine.writes)
        assertTrue(cards.added.isEmpty())
        assertTrue(calls.invoked.isEmpty())
    }

    @Test
    fun `reference is read only and requires neither a project nor an initialized library`() = runTest {
        val knowledge = ServiceUiAgentTools(
            lazy { error("Reference must not load the library") },
            lazy { error("Reference must not resolve owner methods") },
            lazy { error("Reference must not load chat cards") },
            toggles,
            FixedClock,
            BundledUiAuthoringKnowledge(),
        )
        val declaration = knowledge.specifications(null).single { it.name == ServiceUiTools.REFERENCE }
        assertTrue(knowledge.isDetachedSupported)
        assertEquals(AgentToolAction.Read, declaration.action)
        assertEquals(null, context.workspace)
        ServiceUiReferenceTopics.forEach { topic ->
            assertFalse(knowledge.execute(context, ServiceUiTools.REFERENCE, referenceTopic(topic)).isError)
        }

        toggles.isEnabled.value = false
        assertTrue(knowledge.execute(context, ServiceUiTools.REFERENCE, referenceTopic("mini-app-design")).isError)
        assertEquals(emptyList(), knowledge.specifications(null))
    }

    @Test
    fun `reference schema advertises an optional closed topic choice and instructions discover the skill`() = runTest {
        val schema = tools.specifications(null).single { it.name == ServiceUiTools.REFERENCE }.inputSchema
        val properties = schema.getValue("properties").jsonObject
        assertEquals(setOf("topic"), properties.keys)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertTrue(schema.getValue("required").jsonArray.isEmpty())
        val topic = properties.getValue("topic").jsonObject
        assertEquals(JsonPrimitive("string"), topic["type"])
        assertEquals(
            listOf(
                "spec",
                "mini-app-design",
                "service-as-ui",
                "service-as-ui/layout",
                "service-as-ui/actions-data",
                "service-as-ui/webview",
                "service-as-ui/visual-patterns",
                "service-as-ui/examples",
            ),
            topic.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )

        val scope = AgentToolScope(null, declared = setOf(ServiceUiTools.PUT, ServiceUiTools.REFERENCE))
        assertTrue("mini-app-design" in tools.instructions(scope))
        assertTrue("service-as-ui" in tools.instructions(scope))
        assertEquals("", tools.instructions(scope.copy(declared = emptySet())))
        toggles.isEnabled.value = false
        assertEquals("", tools.instructions(scope))
    }

    @Test
    fun `reference rejects malformed topics and unknown arguments`() = runTest {
        val invalidTopics = listOf(
            JsonNull,
            JsonPrimitive(false),
            JsonPrimitive(1),
            JsonPrimitive(""),
            JsonPrimitive("unknown"),
            JsonPrimitive("service-as-ui/../../private"),
            JsonPrimitive(" spec "),
            JsonArray(emptyList()),
            JsonObject(emptyMap()),
        )
        val invalidArguments = invalidTopics.map { JsonObject(mapOf("topic" to it)) } + listOf(
            JsonObject(mapOf("extra" to JsonPrimitive(true))),
            JsonObject(referenceTopic("spec") + ("extra" to JsonPrimitive(true))),
        )
        invalidArguments.forEach { arguments ->
            val result = call(ServiceUiTools.REFERENCE, arguments)
            assertTrue(result.isError, arguments.toString())
            assertTrue("topic" in result.text, result.text)
        }
        assertEquals(0, machine.writes)
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
    fun `context is advertised and saved with reviewed methods and context launchers`() = runTest {
        val properties = tools.specifications(null).first { it.name == ServiceUiTools.PUT }.inputSchema
            .getValue("properties").jsonObject
        val placements = properties.getValue("placement").jsonObject.getValue("enum").jsonArray
        val launchers = properties.getValue("launchers").jsonObject.getValue("items").jsonObject
            .getValue("enum").jsonArray
        assertTrue(JsonPrimitive("context") in placements)
        assertTrue(JsonPrimitive("context") in launchers)
        assertTrue(
            "`context`: a compact, rounded floating card at the top right" in call(ServiceUiTools.REFERENCE).text,
        )

        val saved = call(ServiceUiTools.PUT, put(SPEC, placement = "context"))
        assertFalse(saved.isError, saved.text)
        val contextSurface = (machine.state.value as ServiceUiState.Ready).library.surfaces.single()
        assertEquals(UiPlacement.Context, contextSurface.content.placement)
        val got = Json.parseToJsonElement(
            call(ServiceUiTools.GET, buildJsonObject { put("id", contextSurface.id.value) }).text,
        ).jsonObject
        assertEquals(JsonPrimitive("context"), got["placement"])

        val panel = JsonObject(
            put(SPEC, placement = "panel", revision = 1) +
                ("launchers" to JsonArray(listOf(JsonPrimitive("context")))),
        )
        val updated = call(ServiceUiTools.PUT, panel)
        assertFalse(updated.isError, updated.text)
        assertEquals(
            setOf(UiSlot.Context),
            (machine.state.value as ServiceUiState.Ready).library.surfaces.single().content.launchers,
        )
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
        assertTrue(stranger.text.contains("no owner harness:h_9", ignoreCase = true), stranger.text)

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
    fun `web documents receive automatic review and deletes need the current revision`() = runTest {
        val web = put("""{"version":1,"body":{"type":"web","html":"<p>Hi</p>"}}""", placement = "panel")
        val spec = tools.specifications(null).first { it.name == ServiceUiTools.PUT }

        call(ServiceUiTools.PUT, put(SPEC))
        val stale = call(ServiceUiTools.DELETE, delete(revision = 3))
        assertTrue(stale.isError, stale.text)
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
        val webBody = call(
            ServiceUiTools.SHOW,
            show(spec = """{"version":1,"body":{"type":"web","html":"<p>Report</p>","height":"content"}}"""),
        )
        assertFalse(webBody.isError, webBody.text)

        cards.isFull = true
        assertTrue("already shows 64 cards" in call(ServiceUiTools.SHOW, show(surface = "harness:h_1:start")).text)
    }

    @Test
    fun `library show returns the reused storage identity as a live receipt`() = runTest {
        call(ServiceUiTools.PUT, put(SPEC))
        cards.storedId = "c_existing"
        val result = call(ServiceUiTools.SHOW, show(surface = "harness:h_1:start"))
        assertFalse(result.isError)
        assertEquals("c_existing", ServiceUiTools.shownCardId(ServiceUiTools.SHOW, result.text))
        assertTrue(ServiceUiTools.isLiveCard(ServiceUiTools.SHOW, result.text))
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
        assertEquals(calls.snapshot?.binding, calls.invoked.single().expectedBinding)
        assertEquals(context.session, calls.invoked.single().context.session)
        val stranger = call(
            ServiceUiTools.CALL,
            buildJsonObject {
                put("owner", "nobody")
                put("method", "prs")
            },
        )
        assertTrue(stranger.isError)
    }

    @Test
    fun `missing authorization cannot write and an exact facade approval can`() = runTest {
        val arguments = put(SPEC)
        assertTrue(tools.execute(context, ServiceUiTools.PUT, arguments).isError)
        assertEquals(0, machine.writes)
        val approved = approval(ServiceUiTools.PUT, arguments)
        assertEquals(approved, tools.approval(context, tools.catalogSpec(ServiceUiTools.PUT), arguments))
        assertFalse(execute(ServiceUiTools.PUT, arguments, approved).isError)
        assertEquals(1, machine.writes)
    }

    @Test
    fun `changed caller arguments source or activation cannot reuse a safe method decision`() = runTest {
        val arguments = methodArgs()
        val approved = approval(ServiceUiTools.CALL, arguments)
        val changedArgs = buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            put("args", buildJsonObject { put("page", 2) })
        }
        assertTrue(execute(ServiceUiTools.CALL, changedArgs, approved).isError)
        assertTrue(
            tools.execute(
                context.copy(session = context.session.copy(nativeId = "other"), authorization = approved),
                ServiceUiTools.CALL,
                arguments,
            ).isError,
        )
        val original = assertNotNull(calls.snapshot)
        calls.snapshot = original.copy(source = "changed source")
        assertTrue(execute(ServiceUiTools.CALL, arguments, approved).isError)
        calls.snapshot = original.copy(binding = "replacement-callback")
        assertTrue(execute(ServiceUiTools.CALL, arguments, approved).isError)
        assertTrue(calls.invoked.isEmpty())
        calls.snapshot = original
        assertFalse(execute(ServiceUiTools.CALL, arguments, approved).isError)
        assertEquals(1, calls.invoked.size)
    }

    @Test
    fun `a saved surface changed after display review cannot add an unreviewed card`() = runTest {
        assertFalse(call(ServiceUiTools.PUT, put(SPEC)).isError)
        val arguments = show(surface = "harness:h_1:start")
        val approved = approval(ServiceUiTools.SHOW, arguments)
        val ready = machine.state.value as ServiceUiState.Ready
        machine.state.value = ready.copy(
            library = ready.library.copy(
                surfaces = ready.library.surfaces.map { it.copy(content = it.content.copy(spec = TEXT)) },
            ),
        )
        assertTrue(execute(ServiceUiTools.SHOW, arguments, approved).isError)
        assertTrue(cards.added.isEmpty())
    }

    @Test
    fun `a deletion without facade authorization leaves the persisted interface intact`() = runTest {
        assertFalse(call(ServiceUiTools.PUT, put(SPEC)).isError)
        val before = machine.state.value
        assertTrue(tools.execute(context, ServiceUiTools.DELETE, delete(1)).isError)
        assertEquals(before, machine.state.value)
    }

    @Test
    fun `writes carry the reviewed library revision and a changed library rejects before submission`() = runTest {
        assertFalse(call(ServiceUiTools.PUT, put(SPEC)).isError)
        assertEquals(0L, machine.submitted.single().expectedLibraryRevision)

        val arguments = put(TEXT, revision = 1)
        val approved = approval(ServiceUiTools.PUT, arguments)
        val ready = machine.state.value as ServiceUiState.Ready
        // The same surface revision/content can reappear after an intervening delete/recreate.
        machine.state.value = ready.copy(library = ready.library.copy(revision = ready.library.revision + 2))
        assertTrue(execute(ServiceUiTools.PUT, arguments, approved).isError)
        assertEquals(1, machine.submitted.size)

        assertFalse(call(ServiceUiTools.DELETE, delete(revision = 1)).isError)
        assertEquals(3L, machine.submitted.last().expectedLibraryRevision)
    }

    private fun methodArgs() = buildJsonObject {
        put("owner", "pr_map")
        put("method", "prs")
    }

    private fun referenceTopic(topic: String) = buildJsonObject { put("topic", topic) }

    private fun show(spec: String? = null, surface: String? = null) = buildJsonObject {
        put("title", "PRs")
        spec?.let { put("spec", Json.parseToJsonElement(it)) }
        surface?.let { put("surface", it) }
    }

    private suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): AgentToolResult =
        execute(name, arguments, approval(name, arguments))

    private suspend fun approval(name: String, arguments: JsonObject): AgentToolApproval =
        tools.approval(context, tools.catalogSpec(name), arguments)

    private suspend fun execute(name: String, arguments: JsonObject, approval: AgentToolApproval) =
        tools.execute(context.copy(authorization = approval), name, arguments)

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
        val submitted = mutableListOf<UiWrite>()

        override suspend fun send(intent: ServiceUiIntent): SendResult {
            val edit = intent as ServiceUiIntent.Internal.Edit
            writes++
            submitted += edit.write
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
        var storedId: String? = null

        override suspend fun add(card: UiCard): UiCardAdded = if (isFull) {
            UiCardAdded.ChatFull
        } else {
            added += card
            UiCardAdded.Stored(storedId ?: card.id, 0)
        }

        override fun observe(id: String): Flow<UiCard?> = flowOf(added.firstOrNull { it.id == id })

        override suspend fun latest(chat: SessionRef, surface: UiSurfaceId): UiCard? =
            added.lastOrNull { it.chat == chat && (it.source as? UiCardSource.Surface)?.surface == surface }
    }

    private class Calls : UiMethodCalls {
        var snapshot: UiMethodReview? = UiMethodReview("live-callback", "script ui method source")
        val invoked = mutableListOf<UiMethodCall>()
        override suspend fun review(owner: UiOwner, method: String, context: UiCallContext): UiMethodReview? =
            snapshot.takeIf { method == "prs" }
        override suspend fun call(owner: UiOwner, call: UiMethodCall): UiMethodResult {
            if (call.expectedBinding == null || call.expectedBinding != snapshot?.binding) {
                return UiMethodResult.Failure(UiMethodFailure.Unavailable)
            }
            invoked += call
            return UiMethodResult.Success(JsonPrimitive(1))
        }

        override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> = flowOf(emptySet())
        override fun methods(owner: UiOwner): Flow<UiMethods> =
            flowOf(UiMethods(isAvailable = true, listOf(UiMethodInfo("prs", "Open pull requests"))))

        override suspend fun call(
            owner: UiOwner,
            method: String,
            arguments: JsonObject,
            context: UiCallContext,
        ): UiMethodResult = error("An agent call must preserve its reviewed binding")
    }

    private class Toggles : FeatureToggles {
        val isEnabled = MutableStateFlow(true)

        @Suppress("UNCHECKED_CAST") // Tests expose Boolean feature flags.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
            isEnabled as Flow<T>
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_000)
    }

    private companion object {
        const val TEXT = """{"version":1,"body":{"type":"text","text":"Hi"}}"""
        const val SPEC = """{"version":1,"data":{"prs":{"method":"prs"}},"body":{"type":"column","children":[
            {"type":"text","text":"{{data.prs.count}} open"},
            {"type":"button","label":"Refresh","action":{"type":"reload","source":"prs"}}]}}"""
    }
}
