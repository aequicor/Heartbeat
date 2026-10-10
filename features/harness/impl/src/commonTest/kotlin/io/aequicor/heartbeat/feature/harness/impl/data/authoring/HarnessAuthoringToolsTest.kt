package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class HarnessAuthoringToolsTest {
    private val toggles = AuthoringToggles()
    private val library = FakeLibrary()
    private val host = FakeHost()
    private val tools = HarnessAuthoringTools(
        toggles,
        lazyOf(library),
        lazyOf(HarnessCodeChecks(host) { it.hashCode().toString() }),
        lazyOf(FakeCatalogs()),
        FixedClock,
    )
    private val context = AgentToolContext(dispatchSession, PROJECT, TurnId("turn"), trust = TrustLevel.Full)

    @Test
    fun `texts follow the approval level while code always asks`() = runTest {
        val skill = put("skill", "design", "Design tokens and patterns", description = "Load before UI work")
        library.approval = HarnessApproval.ByTrust
        assertFalse(tools.requiresDecision(context, spec(HarnessTools.ITEM_PUT), skill))
        val autoEdits = context.copy(trust = TrustLevel.AutoEdits)
        assertTrue(tools.requiresDecision(autoEdits, spec(HarnessTools.ITEM_PUT), skill))
        library.approval = HarnessApproval.AcceptAll
        val script = put("script", "verify", "hooks.toString()")
        assertTrue(tools.requiresDecision(context, spec(HarnessTools.ITEM_PUT), script))
        library.approval = HarnessApproval.Ask
        assertTrue(tools.requiresDecision(context, spec(HarnessTools.ITEM_PUT), skill))
    }

    @Test
    fun `approval shows the whole text and binds the level and harness revision`() = runTest {
        val body = "Line one\n\nLine two"
        val approval = tools.approval(context, spec(HarnessTools.ITEM_PUT), put("instruction", "rule", body))
        assertEquals(HarnessTools.ITEM_PUT, approval.name)
        assertTrue(approval.description.orEmpty().contains(body))
        assertEquals("approval=ask;harness=${harness.id.value}@${harness.revision}", approval.binding)
    }

    @Test
    fun `failing code is reported with diagnostics without asking or saving`() = runTest {
        host.result = HarnessCompilationResult.Failure(
            listOf(HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, "Unresolved reference: foo", 3, 7)),
        )
        val arguments = put("script", "verify", "foo()")
        assertFalse(tools.requiresDecision(context, spec(HarnessTools.ITEM_PUT), arguments))
        val approval = tools.approval(context, spec(HarnessTools.ITEM_PUT), arguments)
        val result = tools.execute(context.copy(authorization = approval), HarnessTools.ITEM_PUT, arguments)
        assertTrue(result.isError)
        assertTrue(result.text.contains("3:7 error Unresolved reference: foo"))
        assertTrue(library.sent.isEmpty())
        assertEquals(1, host.compiled.size)
        assertTrue(host.compiled.single().isAgentInitiated)
    }

    @Test
    fun `saved code is compiled once and stored under the approved revision`() = runTest {
        val arguments = put("script", "verify", "hooks.toString()")
        val approval = tools.approval(context, spec(HarnessTools.ITEM_PUT), arguments)
        assertTrue(approval.description.orEmpty().contains("compiled, warnings: 0"))
        val result = tools.execute(context.copy(authorization = approval), HarnessTools.ITEM_PUT, arguments)
        assertFalse(result.isError, result.text)
        val update = assertIs<HarnessIntent.Public.Update>(library.sent.single())
        assertEquals(harness.revision, update.expectedRevision)
        val item = assertIs<HarnessItem.Script>(assertIs<HarnessChange.PutItem>(update.change).item)
        assertEquals("hooks.toString()", item.source)
        assertEquals(1, host.compiled.size)
        assertEquals(1, host.closed)
    }

    @Test
    fun `an approval of an older revision is refused at execution`() = runTest {
        val arguments = put("instruction", "rule", "Use tokens")
        val approval = tools.approval(context, spec(HarnessTools.ITEM_PUT), arguments)
        library.revision = 3
        val result = tools.execute(context.copy(authorization = approval), HarnessTools.ITEM_PUT, arguments)
        assertTrue(result.isError)
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `replacing an item keeps its identity and enabled flag but never its kind`() = runTest {
        val existing = HarnessItem.Instruction(
            io.aequicor.heartbeat.feature.harness.api.ItemId("kept"),
            io.aequicor.heartbeat.feature.harness.api.ItemName("rule"),
            "Old",
            isEnabled = false,
        )
        library.items = listOf(existing)
        val arguments = put("instruction", "rule", "New")
        val approval = tools.approval(context, spec(HarnessTools.ITEM_PUT), arguments)
        tools.execute(context.copy(authorization = approval), HarnessTools.ITEM_PUT, arguments)
        val item = (library.sent.single() as HarnessIntent.Public.Update).change as HarnessChange.PutItem
        assertEquals(existing.copy(text = "New"), item.item)
        val asSkill = put("skill", "rule", "Body", description = "d")
        assertTrue(tools.execute(context.copy(authorization = approval), HarnessTools.ITEM_PUT, asSkill).isError)
    }

    @Test
    fun `creating a project harness normalizes the scope and connects the chat`() = runTest {
        library.harnesses = emptyList()
        val arguments = buildJsonObject {
            put("name", "compose_ui")
            put("title", "Compose UI")
            put("scope", "project")
        }
        val approval = tools.approval(context, spec(HarnessTools.CREATE), arguments)
        val result = tools.execute(context.copy(authorization = approval), HarnessTools.CREATE, arguments)
        assertFalse(result.isError, result.text)
        val create = assertIs<HarnessIntent.Public.Create>(library.sent[0])
        assertEquals(HarnessScope.Projects(setOf(SOURCE)), create.draft.scope)
        assertEquals(dispatchSession, create.author)
        val attach = assertIs<HarnessIntent.Public.Attach>(library.sent[1])
        assertEquals(create.id, attach.id)
        assertEquals(dispatchSession, attach.session)
    }

    @Test
    fun `enabling native tools always asks and unknown or ungated tools are refused`() = runTest {
        library.approval = HarnessApproval.AcceptAll
        val off = tools(native = "WebFetch" to "off")
        assertFalse(tools.requiresDecision(context, spec(HarnessTools.TOOLS_SET), off))
        val on = tools(native = "Bash" to "on")
        assertTrue(tools.requiresDecision(context, spec(HarnessTools.TOOLS_SET), on))
        val approval = tools.approval(context, spec(HarnessTools.TOOLS_SET), on)
        assertTrue(approval.description.orEmpty().contains("claude tool Bash: on (was default)"))
        val result = tools.execute(context.copy(authorization = approval), HarnessTools.TOOLS_SET, on)
        assertTrue(result.text.contains("harness.native_tools"))
        val change = (library.sent.single() as HarnessIntent.Public.Update).change as HarnessChange.Tools
        assertEquals(ToolSwitch.On, change.tools.native["claude"]?.get("Bash"))
        listOf(tools(native = "Agent" to "on"), tools(native = "Nope" to "off"), tools(hosted = "harness_create"))
            .forEach { assertTrue(tools.execute(context, HarnessTools.TOOLS_SET, it).isError) }
    }

    @Test
    fun `rejections become fixed explanations`() = runTest {
        library.outcome = LibraryOutcome.Rejected(HarnessRejection.Conflict)
        val arguments = buildJsonObject { put("harness", harness.name.value) }
        val approval = tools.approval(context, spec(HarnessTools.DELETE), arguments)
        val result = tools.execute(context.copy(authorization = approval), HarnessTools.DELETE, arguments)
        assertTrue(result.isError)
        assertTrue(result.text.contains("changed meanwhile"))
    }

    @Test
    fun `declarations disappear with the toggle and instructions honor declared tools`() = runTest {
        assertEquals(8, tools.specifications(null).size)
        val scope = io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope(null, declared = emptySet())
        assertEquals("", tools.instructions(scope))
        toggles.enabled.value = false
        assertTrue(tools.specifications(null).isEmpty())
        assertNull(library.sent.firstOrNull())
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
        hosted?.let { put("hosted_off", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(it)))) }
        native?.let { (tool, switch) -> putJsonObject("native") { putJsonObject("claude") { put(tool, switch) } } }
    }

    private fun spec(name: String) = (HARNESS_AUTHORING_SPECS).single { it.name == name }
}

private val PROJECT = WorkspaceRef("worktree")
private val SOURCE = WorkspaceRef("project")

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
    var approval = HarnessApproval.Ask
    var revision = harness.revision
    var items: List<HarnessItem> = emptyList()
    var harnesses: List<io.aequicor.heartbeat.feature.harness.api.Harness>? = null
    var outcome: LibraryOutcome? = null
    val sent = mutableListOf<HarnessIntent.Public>()

    override val current: HarnessState.Ready
        get() = HarnessState.Ready(
            harnesses = (harnesses ?: listOf(harness.copy(revision = revision, items = items))).map(::HarnessEntry),
            approval = approval,
        )

    override suspend fun ready(): HarnessState.Ready = current

    override suspend fun submit(intent: HarnessIntent.Public): LibraryOutcome {
        sent += intent
        return outcome ?: LibraryOutcome.Committed(HarnessOutput.Updated(intent.requestId, HarnessId("any")))
    }

    override suspend fun sourceProject(workspace: WorkspaceRef?): WorkspaceRef? = SOURCE.takeIf { workspace == PROJECT }
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

    override suspend fun removeCached(harness: HarnessId, item: io.aequicor.heartbeat.feature.harness.api.ItemId) = Unit
}

private class FakeCatalogs : HarnessToolCatalogs {
    override fun nativeEnabling() = flowOf(true)

    override fun hosted() = listOf(
        ToolGroup("search", "Поиск", listOf(ToolCatalogEntry("web_search", AgentToolAction.Read))),
        ToolGroup("harness", "Харнессы", listOf(ToolCatalogEntry("harness_create", AgentToolAction.Read))),
    )

    override fun engines() = listOf(
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

    override suspend fun effective(scope: ToolPolicyScope) = ResolvedToolPolicy()
}
