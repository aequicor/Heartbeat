package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.LibraryOutcome
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class HarnessAuthoringToolsTest {
    private val toggles = AuthoringToggles()
    private val library = FakeLibrary()
    private val host = FakeHost()
    private val catalogs = FakeCatalogs()
    private var creationScope: HarnessScope? = HarnessScope.Projects(setOf(SOURCE_PROJECT))
    private val tools = HarnessAuthoringTools(
        toggles,
        lazyOf(library),
        lazyOf(HarnessCodeChecks(host) { it.hashCode().toString() }),
        lazyOf(catalogs),
        FixedClock,
        HarnessCreationScope { workspace -> if (workspace == null) HarnessScope.Attached else creationScope },
    )
    private val context = AgentToolContext(dispatchSession, PROJECT, TurnId("turn"), trust = TrustLevel.Full)

    private val reviewContext = context.copy(
        target = EngineTarget(
            dispatchSession.engine,
            EngineBindingId("binding"),
            ModelId("model"),
        ),
    )

    @Test
    fun `code is compiled once then saved under the exact revision`() = runTest {
        val args = put("script", "format", "hooks.toString()")
        val asking = reviewContext.copy(trust = TrustLevel.Ask)
        val approval = tools.approval(asking, spec(HarnessTools.ITEM_PUT), args)
        assertEquals(approval, allow(asking, HarnessTools.ITEM_PUT, args))
        val result = tools.execute(asking.copy(authorization = approval), HarnessTools.ITEM_PUT, args)
        assertFalse(result.isError)
        assertTrue("activation is asynchronous" in result.text)
        assertTrue("harness_get" in result.text)
        assertEquals(1, host.compiled.size)
        assertEquals(1, host.closed)
        val update = assertIs<HarnessIntent.Internal.Update>(library.sent.single())
        assertEquals(harness.revision, update.expectedRevision)
        assertEquals(emptySet(), update.expectedSessions)
        val item = assertIs<HarnessChange.PutItem>(update.change).item
        assertEquals("hooks.toString()", assertIs<HarnessItem.Script>(item).source)
    }

    @Test
    fun `authorization does not require a reviewer model but direct execution still needs an operation`() = runTest {
        val args = put("instruction", "rule", "Use tokens")
        assertTrue(tools.execute(context, HarnessTools.ITEM_PUT, args).isError)
        val approval = tools.approval(context, spec(HarnessTools.ITEM_PUT), args)
        assertFalse(tools.execute(context.copy(authorization = approval), HarnessTools.ITEM_PUT, args).isError)
        assertEquals(1, library.sent.size)
    }

    @Test
    fun `authorization cannot cover changed content caller model scope or revision`() = runTest {
        val args = put("instruction", "rule", "Use tokens")
        val approval = allow(reviewContext, HarnessTools.ITEM_PUT, args)
        val granted = reviewContext.copy(authorization = approval)
        assertTrue(tools.execute(granted, HarnessTools.ITEM_PUT, put("instruction", "rule", "Other")).isError)
        assertTrue(tools.execute(granted.copy(turn = TurnId("other")), HarnessTools.ITEM_PUT, args).isError)
        assertTrue(tools.execute(granted.copy(workspace = WorkspaceRef("other")), HarnessTools.ITEM_PUT, args).isError)
        assertTrue(tools.execute(granted.copy(target = null), HarnessTools.ITEM_PUT, args).isError)
        library.attachments = mapOf(dispatchSession to setOf(harness.id))
        assertTrue(tools.execute(granted, HarnessTools.ITEM_PUT, args).isError)
        library.attachments = emptyMap()
        library.revision++
        val changedApproval = tools.approval(granted, spec(HarnessTools.ITEM_PUT), args)
        assertNotEquals(approval.binding, changedApproval.binding)
        assertTrue(tools.execute(granted, HarnessTools.ITEM_PUT, args).isError)
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `changing previous source without a revision invalidates the operation`() = runTest {
        library.items = listOf(HarnessItem.Script(ItemId("script"), ItemName("guard"), "Previous", "old guard"))
        val args = put("script", "guard", "new guard", "New description")
        val approval = allow(reviewContext, HarnessTools.ITEM_PUT, args)
        library.items = listOf(HarnessItem.Script(ItemId("script"), ItemName("guard"), "Previous", "changed guard"))
        assertTrue(tools.execute(reviewContext.copy(authorization = approval), HarnessTools.ITEM_PUT, args).isError)
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `compile failure refuses before authorization and writes nothing`() = runTest {
        host.result = HarnessCompilationResult.Failure(
            listOf(HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, "Unresolved reference: foo", 3, 7)),
        )
        val args = put("script", "verify", "foo()")
        val denied = tools.execute(reviewContext, HarnessTools.ITEM_PUT, args)
        assertTrue(denied.text.contains("3:7 error Unresolved reference: foo"))
        assertTrue(tools.execute(reviewContext, HarnessTools.ITEM_PUT, args).isError)
        assertTrue(library.sent.isEmpty())
        assertEquals(1, host.compiled.size)
    }

    @Test
    fun `replacing an item preserves identity and enabled flag`() = runTest {
        val old = HarnessItem.Instruction(ItemId("kept"), ItemName("rule"), "Old", isEnabled = false)
        library.items = listOf(old)
        val args = put("instruction", "rule", "New")
        val approval = allow(reviewContext, HarnessTools.ITEM_PUT, args)
        tools.execute(reviewContext.copy(authorization = approval), HarnessTools.ITEM_PUT, args)
        val item = (library.sent.single() as HarnessIntent.Internal.Update).change as HarnessChange.PutItem
        assertEquals(old.copy(text = "New"), item.item)
    }

    @Test
    fun `project create saves its source project and connects only its empty original revision`() = runTest {
        library.harnesses = emptyList()
        val args = buildJsonObject {
            put("name", "compose_ui")
            put("title", "Compose UI")
        }
        val approval = allow(reviewContext, HarnessTools.CREATE, args)
        val result = tools.execute(reviewContext.copy(authorization = approval), HarnessTools.CREATE, args)
        assertFalse(result.isError, result.text)
        val create = assertIs<HarnessIntent.Internal.Create>(library.sent[0])
        assertEquals(HarnessScope.Projects(setOf(SOURCE_PROJECT)), create.draft.scope)
        val attach = assertIs<HarnessIntent.Public.Attach>(library.sent[1])
        assertEquals(create.id, attach.id)
        assertEquals(0L, attach.expectedRevision)
        assertEquals(emptySet(), attach.expectedSessions)
    }

    @Test
    fun `project create without attachment stays in its project library`() = runTest {
        val args = createArgs(isAttached = false)
        val approval = allow(reviewContext, HarnessTools.CREATE, args)
        val result = tools.execute(reviewContext.copy(authorization = approval), HarnessTools.CREATE, args)
        assertFalse(result.isError, result.text)
        val create = assertIs<HarnessIntent.Internal.Create>(library.sent.single())
        assertEquals(HarnessScope.Projects(setOf(SOURCE_PROJECT)), create.draft.scope)
    }

    @Test
    fun `create without a project saves a shared harness`() = runTest {
        val detached = reviewContext.copy(workspace = null)
        val args = createArgs(isAttached = false)
        val approval = allow(detached, HarnessTools.CREATE, args)
        val result = tools.execute(detached.copy(authorization = approval), HarnessTools.CREATE, args)
        assertFalse(result.isError, result.text)
        val create = assertIs<HarnessIntent.Internal.Create>(library.sent.single())
        assertEquals(HarnessScope.Attached, create.draft.scope)
    }

    @Test
    fun `unknown project refuses creation instead of saving a shared harness`() = runTest {
        creationScope = null
        val args = createArgs(isAttached = false)
        assertTrue(tools.execute(reviewContext, HarnessTools.CREATE, args).isError)
        assertTrue(tools.execute(reviewContext, HarnessTools.CREATE, args).isError)
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `changed source project invalidates creation authorization`() = runTest {
        val args = createArgs(isAttached = false)
        val approval = allow(reviewContext, HarnessTools.CREATE, args)
        creationScope = HarnessScope.Projects(setOf(WorkspaceRef("other_project")))
        val changed = tools.approval(reviewContext, spec(HarnessTools.CREATE), args)
        assertNotEquals(approval.binding, changed.binding)
        assertTrue(tools.execute(reviewContext.copy(authorization = approval), HarnessTools.CREATE, args).isError)
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `attach and detach carry the reviewed version and connections`() = runTest {
        library.attachments = mapOf(dispatchSession to setOf(harness.id))
        val args = buildJsonObject { put("harness", harness.name.value) }
        for (name in listOf(HarnessTools.ATTACH, HarnessTools.DETACH)) {
            val approval = allow(reviewContext, name, args)
            assertFalse(tools.execute(reviewContext.copy(authorization = approval), name, args).isError)
        }
        val attach = assertIs<HarnessIntent.Public.Attach>(library.sent[0])
        val detach = assertIs<HarnessIntent.Public.Detach>(library.sent[1])
        assertEquals(harness.revision, attach.expectedRevision)
        assertEquals(setOf(dispatchSession), attach.expectedSessions)
        assertEquals(attach.expectedRevision, detach.expectedRevision)
        assertEquals(attach.expectedSessions, detach.expectedSessions)
    }

    @Test
    fun `catalog changes cannot alter a reviewed resulting policy`() = runTest {
        library.tools = ToolPolicySpec(native = mapOf("pi" to mapOf("bash" to ToolSwitch.Off)))
        val args = tools(native = "WebFetch" to "off")
        val approval = allow(reviewContext, HarnessTools.TOOLS_SET, args)
        catalogs.available = catalogs.available + EngineDescriptor(
            EngineId("pi"),
            "Pi",
            EngineFamily.Vendor,
            emptySet(),
            HarnessNativeTools,
            nativeTools = listOf(NativeToolSpec("bash", AgentToolAction.Command, true, isGated = true)),
        )
        assertTrue(tools.execute(reviewContext.copy(authorization = approval), HarnessTools.TOOLS_SET, args).isError)
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `library unavailable malformed input and unsafe tool declarations refuse without writes`() = runTest {
        library.isAvailable = false
        val args = put("instruction", "rule", "Use tokens")
        assertTrue(tools.execute(reviewContext, HarnessTools.ITEM_PUT, args).isError)
        library.isAvailable = true
        for ((name, invalid) in listOf(
            HarnessTools.ITEM_PUT to buildJsonObject { put("harness", "X") },
            HarnessTools.TOOLS_SET to tools(native = "Agent" to "on"),
            HarnessTools.TOOLS_SET to tools(hosted = "harness_create"),
            HarnessTools.CREATE to buildJsonObject {
                put("name", "new_app")
                put("title", "New")
                put("scope", "profile")
            },
        )) {
            assertTrue(tools.execute(reviewContext, name, invalid).isError)
        }
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `declarations disappear with toggle and instructions honor declared tools`() = runTest {
        assertEquals(8, tools.specifications(null).size)
        val scope = AgentToolScope(null, declared = emptySet())
        assertEquals("", tools.instructions(scope))
        toggles.enabled.value = false
        assertTrue(tools.specifications(null).isEmpty())
    }

    private suspend fun allow(context: AgentToolContext, name: String, args: JsonObject): AgentToolApproval {
        val snapshot = tools.approval(context, spec(name), args)
        return snapshot
    }

    private fun createArgs(isAttached: Boolean): JsonObject = buildJsonObject {
        put("name", "project_rules")
        put("title", "Project rules")
        put("attach", isAttached)
    }

    private fun put(kind: String, name: String, content: String, description: String? = null): JsonObject =
        buildJsonObject {
            put("harness", harness.name.value)
            put("kind", kind)
            put("name", name)
            put("content", content)
            description?.let { put("description", it) }
        }

    private fun tools(native: Pair<String, String>? = null, hosted: String? = null): JsonObject = buildJsonObject {
        put("harness", harness.name.value)
        hosted?.let { put("hosted_off", JsonArray(listOf(JsonPrimitive(it)))) }
        native?.let { (tool, switch) -> putJsonObject("native") { putJsonObject("claude") { put(tool, switch) } } }
    }

    private fun spec(name: String) = (HARNESS_AUTHORING_SPECS).single { it.name == name }
}

private val PROJECT = WorkspaceRef("worktree")
private val SOURCE_PROJECT = WorkspaceRef("source_project")

private object FixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(10_000)
}

private class AuthoringToggles : FeatureToggles {
    val enabled = MutableStateFlow(true)

    @Suppress("UNCHECKED_CAST") // The tools read only boolean flags.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = when (toggle) {
        HarnessEnabled -> enabled
        HarnessNativeTools -> MutableStateFlow(false)
        else -> error("Unexpected toggle")
    } as Flow<T>

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private class FakeLibrary : HarnessLibraryClient {
    var attachments = emptyMap<SessionRef, Set<HarnessId>>()
    var revision = harness.revision
    var items: List<HarnessItem> = emptyList()
    var tools = ToolPolicySpec()
    var scope: HarnessScope = harness.scope
    var isAvailable = true
    var harnesses: List<Harness>? = null
    var outcome: LibraryOutcome? = null
    val outcomes = ArrayDeque<LibraryOutcome>()
    val sent = mutableListOf<HarnessIntent.Command>()

    override val current: HarnessState.Ready?
        get() {
            val default = harness.copy(revision = revision, items = items, tools = tools, scope = scope)
            val entries = (harnesses ?: listOf(default)).map(::HarnessEntry)
            return HarnessState.Ready(harnesses = entries, attachments = attachments)
                .takeIf { isAvailable }
        }

    override suspend fun ready(): HarnessState.Ready? = current

    override suspend fun submit(intent: HarnessIntent.Command): LibraryOutcome {
        sent += intent
        return outcomes.removeFirstOrNull() ?: outcome
            ?: LibraryOutcome.Committed(HarnessOutput.Updated(intent.requestId, HarnessId("any")))
    }
}

private class FakeHost : HarnessScriptHost {
    var result: HarnessCompilationResult? = null
    val compiled = mutableListOf<HarnessCompilationRequest>()
    var closed = 0
    override val isAvailable = true

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
        compiled += request
        return result ?: HarnessCompilationResult.Success(
            object : CompiledHarnessCode {
                override val kind = HarnessCodeKind.Script
                override fun retain() = this
                override fun close() {
                    closed++
                }
            },
            emptyList(),
        )
    }

    override suspend fun evaluate(code: CompiledHarnessCode, context: HarnessEvaluationContext) =
        HarnessEvaluationResult.Unsupported

    override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
}

private class FakeCatalogs : HarnessToolCatalogs {
    var available = listOf(
        EngineDescriptor(
            EngineId("claude"),
            "Claude",
            EngineFamily.Vendor,
            emptySet(),
            HarnessNativeTools,
            nativeTools = listOf(
                NativeToolSpec("Bash", AgentToolAction.Command, isEnabledByDefault = false, isGated = true),
                NativeToolSpec("WebFetch", AgentToolAction.Command, isEnabledByDefault = true, isGated = true),
                NativeToolSpec("Agent", AgentToolAction.Command, isEnabledByDefault = true, isGated = false),
            ),
        ),
    )

    override fun nativeEnabling() = flowOf(true)

    override fun hosted() = listOf(
        ToolGroup("search", "Поиск", listOf(ToolCatalogEntry("web_search", AgentToolAction.Read))),
        ToolGroup("harness", "Харнессы", listOf(ToolCatalogEntry("harness_create", AgentToolAction.Read))),
    )

    override fun engines() = available

    override suspend fun effective(scope: ToolPolicyScope) = ResolvedToolPolicy()
}
