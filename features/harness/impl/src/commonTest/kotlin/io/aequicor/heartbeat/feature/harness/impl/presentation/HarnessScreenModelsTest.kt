package io.aequicor.heartbeat.feature.harness.impl.presentation

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
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.LibraryOutcome
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HarnessScreenModelsTest {
    private val rule = HarnessItem.Instruction(ItemId("rule"), ItemName("rule"), "Use tokens")
    private val script = HarnessItem.Script(ItemId("verify"), ItemName("verify"), "Checks", "hooks.toString()")
    private val ready = HarnessState.Ready(
        harnesses = listOf(HarnessEntry(harness.copy(items = listOf(rule, script), revision = 4))),
        approval = HarnessApproval.ByTrust,
        isRuntimeAvailable = true,
        approvalRevision = 2,
    )

    @Test
    fun `library mirrors the harnesses and sends switches with the shown revisions`() = runTest {
        val library = RecordingLibrary(ready)
        val model = HarnessLibraryModel(library, storeFactory(), backgroundScope)
        val screen = subscribe(model.store)
        assertEquals(ApprovalUi.ByTrust, screen.states.value.approval)
        assertEquals(listOf(harness.id.value), screen.states.value.harnesses.map { it.id })

        screen.intent(HarnessLibraryIntent.SelectApproval(ApprovalUi.AcceptAll))
        screen.intent(HarnessLibraryIntent.SetEnabled(harness.id.value, false))
        val opened = backgroundScope.async { screen.actions.first() }
        screen.intent(HarnessLibraryIntent.Open(harness.id.value))
        runCurrent()
        val approval = assertIs<HarnessIntent.Public.SetApproval>(library.sent[0])
        assertEquals(HarnessApproval.AcceptAll to 2L, approval.level to approval.expectedRevision)
        val enabled = assertIs<HarnessIntent.Public.SetEnabled>(library.sent[1])
        assertEquals(false to 4L, enabled.isEnabled to enabled.expectedRevision)
        assertEquals(HarnessLibraryAction.OpenDetail(harness.id.value), opened.await())
    }

    @Test
    fun `failing code shows diagnostics, keeps the draft and saves nothing`() = runTest {
        val library = RecordingLibrary(ready)
        val host = EditorHost(
            HarnessCompilationResult.Failure(
                listOf(HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, "Unresolved", 1, 3)),
            ),
        )
        val drafts = HarnessDrafts()
        val model = HarnessItemModel(
            harness.id.value,
            script.id.value,
            library,
            HarnessItemEditing(library, HarnessCodeChecks(host) { it }, drafts),
            storeFactory(),
            backgroundScope,
        )
        val screen = subscribe(model.store)
        assertTrue(screen.states.value.isCode)
        screen.intent(HarnessItemIntent.ChangeText("broken("))
        screen.intent(HarnessItemIntent.Save)
        runCurrent()
        assertEquals(listOf(1), screen.states.value.diagnostics.map { it.line })
        assertTrue(library.sent.isEmpty())
        assertTrue(screen.states.value.isDirty)
        assertTrue(host.requests.single().source == "broken(" && !host.requests.single().isAgentInitiated)

        val reopened = HarnessItemModel(
            harness.id.value,
            script.id.value,
            library,
            HarnessItemEditing(library, HarnessCodeChecks(host) { it }, drafts),
            storeFactory(),
            backgroundScope,
        )
        val restored = subscribe(reopened.store)
        assertEquals("broken(", restored.states.value.text)
        assertTrue(restored.states.value.isDraftRestored)
    }

    @Test
    fun `text edits save on the starting revision and a conflict asks before overwriting`() = runTest {
        val library = RecordingLibrary(ready)
        library.outcome = LibraryOutcome.Rejected(HarnessRejection.Conflict)
        val model = HarnessItemModel(
            harness.id.value,
            rule.id.value,
            library,
            HarnessItemEditing(library, HarnessCodeChecks(EditorHost(null)) { it }, HarnessDrafts()),
            storeFactory(),
            backgroundScope,
        )
        val screen = subscribe(model.store)
        screen.intent(HarnessItemIntent.ChangeText("Use only tokens"))
        runCurrent()
        library.state.value = ready.copy(
            harnesses = ready.harnesses.map { it.copy(harness = it.harness.copy(revision = 5)) },
        )
        screen.intent(HarnessItemIntent.Save)
        runCurrent()
        val first = assertIs<HarnessIntent.Public.Update>(library.sent.single())
        assertEquals(4L, first.expectedRevision)
        assertEquals(rule.copy(text = "Use only tokens"), (first.change as HarnessChange.PutItem).item)
        assertTrue(screen.states.value.isConflict)

        library.outcome = LibraryOutcome.Committed(HarnessOutput.Updated(request(), harness.id))
        screen.intent(HarnessItemIntent.Overwrite)
        runCurrent()
        assertEquals(5L, (library.sent.last() as HarnessIntent.Public.Update).expectedRevision)
        assertTrue(!screen.states.value.isDirty && !screen.states.value.isConflict)
    }

    @Test
    fun `tool switches replace the stored policy and gated tools are offered on`() = runTest {
        val library = RecordingLibrary(ready)
        val model = HarnessToolsModel(
            harness.id.value,
            library,
            Catalogs,
            storeFactory(),
            backgroundScope,
        )
        val screen = subscribe(model.store)
        val claude = screen.states.value.engines.single()
        assertEquals(listOf(true, false), claude.tools.map { it.isOnAllowed })
        screen.intent(HarnessToolsIntent.SetNative("claude", "Bash", NativeChoiceUi.On))
        runCurrent()
        val update = assertIs<HarnessIntent.Public.Update>(library.sent.single())
        val tools = (update.change as HarnessChange.Tools).tools
        assertEquals(mapOf("claude" to mapOf("Bash" to ToolSwitch.On)), tools.native)
    }
}

private class EditorHost(private val result: HarnessCompilationResult?) : HarnessScriptHost {
    val requests = mutableListOf<HarnessCompilationRequest>()
    override val isAvailable = true

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
        requests += request
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

private object Catalogs : HarnessToolCatalogs {
    override fun hosted() = emptyList<ToolGroup>()

    override fun engines() = listOf(
        EngineDescriptor(
            EngineId("claude"),
            "Claude",
            EngineFamily.Vendor,
            emptySet(),
            HarnessNativeTools,
            nativeTools = listOf(
                NativeToolSpec("Bash", AgentToolAction.Command, isEnabledByDefault = false, isGated = true),
                NativeToolSpec("Agent", AgentToolAction.Command, isEnabledByDefault = true, isGated = false),
            ),
        ),
    )

    override suspend fun effective(scope: ToolPolicyScope) = ResolvedToolPolicy()

    override fun nativeEnabling() = flowOf(true)
}
