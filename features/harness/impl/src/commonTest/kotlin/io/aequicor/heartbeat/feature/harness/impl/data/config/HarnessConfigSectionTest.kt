package io.aequicor.heartbeat.feature.harness.impl.data.config

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineSpec
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.LibraryOutcome
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigContentKind
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessConfigSectionTest {
    private val at = Instant.fromEpochMilliseconds(1_000)
    private val review = Harness(
        HarnessId("h_review"),
        HarnessName("review"),
        "Review",
        "Code review helpers",
        HarnessScope.Attached,
        false,
        listOf(
            HarnessItem.Skill(ItemId("s"), ItemName("tests"), "When testing", "Run the tests\n"),
            HarnessItem.Instruction(ItemId("i"), ItemName("rules"), "Use tokens", isEnabled = false),
            HarnessItem.Template(ItemId("t"), ItemName("pr"), "PR text", "Fix {{issue}}", setOf("issue", "area")),
            HarnessItem.Script(ItemId("c"), ItemName("verify"), "Checks", "check(true)"),
            HarnessItem.Workflow(
                ItemId("w"),
                ItemName("flow"),
                "Flow",
                "workflow {}",
                JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ),
        ),
        ToolPolicySpec(setOf("web_search"), mapOf("claude" to mapOf("Bash" to ToolSwitch.On))),
        null,
        3,
        at,
        at,
    )
    private val library = SpecLibrary(HarnessState.Ready(listOf(HarnessEntry(review)), isRuntimeAvailable = true))
    private val host = FakeHost()
    private val toggles = Toggles()
    private var creationScope: HarnessScope? = HarnessScope.Attached
    private val resolvedProjects = mutableListOf<WorkspaceRef?>()
    private val section = HarnessConfigSection(
        lazyOf(library),
        library,
        lazyOf(HarnessCodeChecks(host) { it }),
        Catalogs(),
        toggles,
        HarnessCreationScope { project ->
            resolvedProjects += project
            creationScope
        },
    )

    @Test
    fun `a harness encodes to readable files that check back to the same canonical unit`() = runTest {
        val entity = section.entities.first().orEmpty().single()
        assertEquals("review", entity.name)
        assertEquals("review", entity.identity)

        val unit = section.encode(entity.key, "ignored")!!
        assertEquals(
            listOf("flow.kts", "harness.json", "pr.md", "rules.md", "tests.md", "verify.kts"),
            unit.files.map { it.path.segments.last() },
        )
        assertEquals("Run the tests\n\n", unit.files.single { it.path.value.endsWith("tests.md") }.text)
        val check = assertIs<ConfigCheck.Valid>(section.check(unit))
        assertEquals(unit, check.canonical)
        assertEquals("review", check.identity)
        assertTrue(check.summary.hasCode)
        assertTrue(check.summary.contents.any { it.kind == ConfigContentKind.ToolPolicy })
        assertEquals(listOf(HarnessCodeKind.Script, HarnessCodeKind.Workflow), host.compiled.map { it.kind })
    }

    @Test
    fun `a new unit creates an enabled harness and a bound one is replaced keeping item identities`() = runTest {
        val unit = section.encode(ConfigEntityKey("h_review"), "review")!!
        val renamed = unit.renamed("audit")

        val created = assertIs<ConfigApplyResult.Applied>(section.apply(renamed, null, ConfigApplyContext(null)))
        val audit = library.harness(created.key.value)
        assertEquals(HarnessName("audit"), audit.name)
        assertEquals(HarnessScope.Attached, audit.scope)
        assertEquals(listOf<WorkspaceRef?>(null), resolvedProjects)
        assertTrue(audit.isEnabled)
        assertTrue(audit.items.none { it.id.value in setOf("s", "i", "t", "c", "w") })

        val trimmed = harnessUnit(
            "review",
            "harnesses/review/harness.json" to """{"title":"Review v2","items":[{"name":"tests","kind":"skill"}]}""",
            "harnesses/review/tests.md" to "Run every test",
        )
        val replaced = section.apply(trimmed, ConfigEntityKey("h_review"), ConfigApplyContext(null))
        assertEquals(ConfigApplyResult.Applied(ConfigEntityKey("h_review")), replaced)
        val updated = library.harness("h_review")
        assertEquals("Review v2", updated.title)
        assertEquals(listOf(ItemId("s")), updated.items.map { it.id })
        assertEquals(ToolPolicySpec(), updated.tools)
        assertEquals(false, updated.isEnabled)
        assertIs<HarnessChange.Content>((library.sent.last() as HarnessIntent.Internal.Update).change)
        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.Invalid),
            section.apply(renamed, ConfigEntityKey("h_review"), ConfigApplyContext(null)),
        )
    }

    @Test
    fun `project folder imports use the resolved source project including worktrees`() = runTest {
        val source = WorkspaceRef("source")
        creationScope = HarnessScope.Projects(setOf(source))
        val unit = section.encode(ConfigEntityKey("h_review"), "review")!!
        for ((index, project) in listOf(source, WorkspaceRef("checkout")).withIndex()) {
            val result = section.apply(unit.renamed("audit$index"), null, ConfigApplyContext(project))
            val created = assertIs<ConfigApplyResult.Applied>(result)

            assertEquals(creationScope, library.harness(created.key.value).scope)
            assertEquals(project, resolvedProjects.last())
        }
    }

    @Test
    fun `an unknown project refuses import without creating a shared harness`() = runTest {
        creationScope = null
        val unit = section.encode(ConfigEntityKey("h_review"), "review")!!.renamed("audit")

        val result = section.apply(unit, null, ConfigApplyContext(WorkspaceRef("unknown")))

        assertEquals(ConfigApplyResult.Rejected(ConfigRejection.Unavailable), result)
        assertTrue(library.sent.isEmpty())
        assertEquals(listOf(review), library.ready.harnesses.map { it.harness })
    }

    @Test
    fun `replacing a bound harness preserves its stored project scope`() = runTest {
        val scoped = review.copy(scope = HarnessScope.Projects(setOf(WorkspaceRef("original"))))
        library.state.value = library.ready.copy(harnesses = listOf(HarnessEntry(scoped)))
        val unit = section.encode(ConfigEntityKey("h_review"), "review")!!

        val result = section.apply(unit, ConfigEntityKey("h_review"), ConfigApplyContext(WorkspaceRef("other")))

        assertEquals(ConfigApplyResult.Applied(ConfigEntityKey("h_review")), result)
        assertEquals(scoped.scope, library.harness("h_review").scope)
        assertTrue(resolvedProjects.isEmpty())
    }

    @Test
    fun `invalid units name the file and never the code`() = runTest {
        val unit = section.encode(ConfigEntityKey("h_review"), "review")!!
        assertEquals(ConfigProblemCode.Name, problems(unit.renamed("Review")).single())
        assertEquals(
            listOf(ConfigProblemCode.Missing),
            problems(ConfigUnit.of(unit.ref, unit.files.filterNot { it.path.value.endsWith("tests.md") })),
        )
        assertEquals(
            listOf(ConfigProblemCode.Unsupported),
            problems(ConfigUnit.of(unit.ref, unit.files + file("harnesses/review/notes.txt", "x"))),
        )
        val duplicates = """{"title":"R","items":[{"name":"a","kind":"skill"},{"name":"a","kind":"instruction"}]}"""
        assertEquals(
            ConfigProblemCode.Name,
            problems(harnessUnit("review", "harnesses/review/harness.json" to duplicates)).first(),
        )
        host.result = HarnessCompilationResult.Failure(
            listOf(HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, "secret code fragment", 3, 7)),
        )
        val failed = assertIs<ConfigCheck.Invalid>(section.check(unit)).problems
        assertEquals(setOf(ConfigProblemCode.Compile), failed.map { it.code }.toSet())
        assertEquals("3:7", failed.first().detail)
        assertTrue(failed.none { "secret" in it.detail })
    }

    @Test
    fun `tool policies that disable harness tools or enable unknown native tools are refused`() = runTest {
        fun policy(tools: String) = harnessUnit(
            "review",
            "harnesses/review/harness.json" to """{"title":"R","tools":$tools}""",
        )

        assertEquals(listOf(ConfigProblemCode.Unsupported), problems(policy("""{"hostedOff":["harness_create"]}""")))
        assertEquals(
            listOf(ConfigProblemCode.Unsupported),
            problems(policy("""{"native":{"other":{"Bash":"on"}}}""")),
        )
        assertIs<ConfigCheck.Valid>(section.check(policy("""{"native":{"other":{"Bash":"off"}}}""")))
        assertIs<ConfigCheck.Valid>(section.check(policy("""{"hostedOff":["unknown_tool"]}""")))
    }

    @Test
    fun `removal deletes the harness and harnesses off make the section unavailable`() = runTest {
        assertEquals(
            ConfigApplyResult.Applied(ConfigEntityKey("h_review")),
            section.remove(ConfigEntityKey("h_review")),
        )
        assertTrue(library.ready.harnesses.isEmpty())
        assertEquals(ConfigApplyResult.Rejected(ConfigRejection.NotFound), section.remove(ConfigEntityKey("h_review")))

        toggles.isEnabled = false
        assertNull(section.entities.first())
        val unit = ConfigUnit.of(
            ConfigUnitRef(ConfigSectionId.Harnesses, "audit"),
            listOf(file("harnesses/audit/harness.json", """{"title":"A"}""")),
        )
        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.Unavailable),
            section.apply(unit, null, ConfigApplyContext(null)),
        )
    }

    private suspend fun problems(unit: ConfigUnit) = assertIs<ConfigCheck.Invalid>(
        section.check(unit),
    ).problems.map { it.code }

    private fun file(path: String, text: String) = ConfigFile(ConfigPath(path), text)

    private fun ConfigUnit.renamed(name: String) = ConfigUnit.of(
        ConfigUnitRef(ref.section, name),
        files.map { file(it.path.value.replace("harnesses/${ref.name}/", "harnesses/$name/"), it.text) },
    )

    private fun harnessUnit(name: String, vararg files: Pair<String, String>) =
        ConfigUnit.of(ConfigUnitRef(ConfigSectionId.Harnesses, name), files.map { file(it.first, it.second) })
}

/** Runs the real library spec; saves and removals are confirmed at once. */
private class SpecLibrary(initial: HarnessState.Ready) :
    HarnessLibraryClient,
    HarnessMachine {
    override val name: String = HarnessMachineSpec.name
    override val state = MutableStateFlow<HarnessState>(initial)
    override val outputs = MutableSharedFlow<HarnessOutput>(extraBufferCapacity = 16)
    val sent = mutableListOf<HarnessIntent.Command>()

    val ready: HarnessState.Ready get() = state.value as HarnessState.Ready

    fun harness(id: String): Harness = ready.harnesses.single { it.harness.id.value == id }.harness

    override val current: HarnessState.Ready? get() = state.value as? HarnessState.Ready

    override suspend fun ready(): HarnessState.Ready? = current

    override suspend fun submit(intent: HarnessIntent.Command): LibraryOutcome {
        sent += intent
        val emitted = run(intent)
        return emitted.firstNotNullOfOrNull { output ->
            when (output) {
                is HarnessOutput.Rejected -> LibraryOutcome.Rejected(output.reason)

                is HarnessOutput.Created, is HarnessOutput.Updated, is HarnessOutput.Deleted ->
                    LibraryOutcome.Committed(output)

                else -> null
            }
        } ?: LibraryOutcome.Unconfirmed
    }

    override suspend fun send(intent: HarnessIntent): SendResult {
        run(intent)
        return SendResult.Accepted
    }

    private fun run(intent: HarnessIntent): List<HarnessOutput> {
        val resolution = HarnessMachineSpec.resolve(state.value, intent) ?: return emptyList()
        state.value = resolution.to
        val receipts = resolution.effects.mapNotNull { effect ->
            when (effect) {
                is HarnessEffect.Save -> HarnessIntent.Internal.Saved(effect.receipt)
                is HarnessEffect.Remove -> HarnessIntent.Internal.Removed(effect.receipt)
                else -> null
            }
        }
        return resolution.outputs + receipts.flatMap(::run)
    }
}

private class FakeHost : HarnessScriptHost {
    var result: HarnessCompilationResult? = null
    val compiled = mutableListOf<HarnessCompilationRequest>()
    override val isAvailable = true

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
        compiled += request
        return result ?: HarnessCompilationResult.Success(
            object : CompiledHarnessCode {
                override val kind = request.kind
                override fun retain() = this
                override fun close() = Unit
            },
            emptyList(),
        )
    }

    override suspend fun evaluate(code: CompiledHarnessCode, context: HarnessEvaluationContext) =
        HarnessEvaluationResult.Unsupported

    override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
}

private class Catalogs : HarnessToolCatalogs {
    override fun hosted(): List<ToolGroup> = emptyList()

    override fun engines() = listOf(
        EngineDescriptor(
            EngineId("claude"),
            "Claude",
            EngineFamily.Vendor,
            emptySet(),
            HarnessNativeTools,
            nativeTools = listOf(
                NativeToolSpec("Bash", AgentToolAction.Command, isEnabledByDefault = false, isGated = true),
            ),
        ),
    )

    override suspend fun effective(scope: ToolPolicyScope): ResolvedToolPolicy = error("unused")

    override fun nativeEnabling() = flowOf(true)
}

private class Toggles(var isEnabled: Boolean = true) : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // The fake answers only the harness flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(isEnabled as T)

    @Suppress("UNCHECKED_CAST") // The fake answers only the harness flag.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = isEnabled as T
}
