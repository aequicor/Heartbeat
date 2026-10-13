package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
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
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiWrite
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteResult
import io.aequicor.heartbeat.feature.serviceasui.api.apply
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    private val reviewer = Reviewer()
    private val tools = ServiceUiAgentTools(
        lazyOf(machine),
        lazyOf(calls),
        lazyOf(cards),
        toggles,
        FixedClock,
        ServiceUiToolSafety(reviewer),
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
        assertIs<AgentToolAutomaticAuthorization.Allowed>(tools.automaticAuthorization(context, spec, web))
        assertIs<AgentToolAutomaticAuthorization.Allowed>(tools.automaticAuthorization(context, spec, put(SPEC)))
        assertEquals(2, reviewer.proposals.size)

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
        assertIs<AgentToolAutomaticAuthorization.Allowed>(tools.automaticAuthorization(context, spec, web))

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
        assertEquals(calls.snapshot?.binding, calls.invoked.single().expectedBinding)
        assertEquals(context.session, calls.invoked.single().context.session)
        assertTrue("script ui method source" in reviewer.proposals.single())
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
    fun `approval recomputes a snapshot but cannot grant execution or run another review`() = runTest {
        val arguments = put(SPEC)
        val recomputed = tools.approval(context, tools.catalogSpec(ServiceUiTools.PUT), arguments)
        assertEquals(0, reviewer.proposals.size)
        assertTrue(execute(ServiceUiTools.PUT, arguments, recomputed).isError)
        assertEquals(0, machine.writes)

        val approved = approval(ServiceUiTools.PUT, arguments)
        assertEquals(approved, tools.approval(context, tools.catalogSpec(ServiceUiTools.PUT), arguments))
        assertEquals(approved, approval(ServiceUiTools.PUT, arguments))
        assertEquals(1, reviewer.proposals.size)
        assertFalse(execute(ServiceUiTools.PUT, arguments, approved).isError)
        assertEquals(1, reviewer.proposals.size)
        assertEquals(1, machine.writes)
    }

    @Test
    fun `risk uncertainty and absent model leave writes cards and owner methods untouched`() = runTest {
        reviewer.verdict = AgentSafetyReview("risky", "Dangerous operation")
        assertTrue(call(ServiceUiTools.PUT, put(SPEC)).isError)
        val refused = tools.approval(context, tools.catalogSpec(ServiceUiTools.PUT), put(SPEC))
        assertTrue(execute(ServiceUiTools.PUT, put(SPEC), refused).isError)
        reviewer.verdict = AgentSafetyReview("uncertain", "Unknown effect")
        assertTrue(call(ServiceUiTools.SHOW, show(spec = TEXT)).isError)
        val missingTarget = context.copy(target = null)
        val arguments = methodArgs()
        val decision = tools.automaticAuthorization(missingTarget, tools.catalogSpec(ServiceUiTools.CALL), arguments)
        assertIs<AgentToolAutomaticAuthorization.Denied>(decision)
        val missingApproval = tools.approval(missingTarget, tools.catalogSpec(ServiceUiTools.CALL), arguments)
        assertTrue(
            tools.execute(
                missingTarget.copy(authorization = missingApproval),
                ServiceUiTools.CALL,
                arguments,
            ).isError,
        )
        assertEquals(2, reviewer.proposals.size, "A missing model cannot start an independent review")
        assertEquals(0, machine.writes)
        assertTrue(cards.added.isEmpty())
        assertTrue(calls.invoked.isEmpty())
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
        assertEquals(1, reviewer.proposals.size, "Execution never silently reviews its changed proposal")
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
        assertEquals(2, reviewer.proposals.size)
    }

    @Test
    fun `unreviewable methods and cancelled reviews never execute`() = runTest {
        calls.snapshot = null
        assertTrue(call(ServiceUiTools.CALL, methodArgs()).isError)
        assertEquals(0, reviewer.proposals.size)
        reviewer.failure = CancellationException("review cancelled")
        assertFailsWith<CancellationException> { call(ServiceUiTools.PUT, put(SPEC)) }
        assertEquals(0, machine.writes)
        assertTrue(cards.added.isEmpty())
        assertTrue(calls.invoked.isEmpty())
    }

    @Test
    fun `a refused deletion leaves the persisted interface intact`() = runTest {
        assertFalse(call(ServiceUiTools.PUT, put(SPEC)).isError)
        reviewer.verdict = AgentSafetyReview("risky", "Keep the existing interface")
        assertTrue(call(ServiceUiTools.DELETE, delete(revision = 1)).isError)
        assertEquals(1, machine.writes)
        assertEquals(1, (machine.state.value as ServiceUiState.Ready).library.surfaces.size)
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

    private fun show(spec: String? = null, surface: String? = null) = buildJsonObject {
        put("title", "PRs")
        spec?.let { put("spec", Json.parseToJsonElement(it)) }
        surface?.let { put("surface", it) }
    }

    private suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): AgentToolResult =
        when (val decision = tools.automaticAuthorization(context, tools.catalogSpec(name), arguments)) {
            is AgentToolAutomaticAuthorization.Allowed -> execute(name, arguments, decision.approval)
            is AgentToolAutomaticAuthorization.Denied -> AgentToolResult(decision.reason, isError = true)
            null -> tools.execute(context, name, arguments)
        }

    private suspend fun approval(name: String, arguments: JsonObject): AgentToolApproval =
        assertIs<AgentToolAutomaticAuthorization.Allowed>(
            tools.automaticAuthorization(context, tools.catalogSpec(name), arguments),
        ).approval

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

    private class Reviewer : AgentSafetyReviewer {
        var verdict = AgentSafetyReview("safe", "Bounded local interface")
        var failure: Exception? = null
        val proposals = mutableListOf<String>()
        override suspend fun review(target: EngineTarget, policy: String, change: String): AgentSafetyReview {
            proposals += change
            failure?.let { throw it }
            return verdict
        }
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
        const val TEXT = """{"version":1,"body":{"type":"text","text":"Hi"}}"""
        const val SPEC = """{"version":1,"data":{"prs":{"method":"prs"}},"body":{"type":"column","children":[
            {"type":"text","text":"{{data.prs.count}} open"},
            {"type":"button","label":"Refresh","action":{"type":"reload","source":"prs"}}]}}"""
    }
}
