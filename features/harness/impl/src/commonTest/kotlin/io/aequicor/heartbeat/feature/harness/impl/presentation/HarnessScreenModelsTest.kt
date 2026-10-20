package io.aequicor.heartbeat.feature.harness.impl.presentation

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
        isRuntimeAvailable = true,
    )

    @Test
    fun `library mirrors the harnesses and sends switches with the shown revisions`() = runTest {
        val library = RecordingLibrary(ready)
        val model = HarnessLibraryModel(library, storeFactory(), backgroundScope)
        val screen = subscribe(model.store)
        assertEquals(listOf(harness.id.value), screen.states.value.harnesses.map { it.id })

        screen.intent(HarnessLibraryIntent.SetEnabled(harness.id.value, false))
        val opened = backgroundScope.async { screen.actions.first() }
        screen.intent(HarnessLibraryIntent.Open(harness.id.value))
        runCurrent()
        val enabled = assertIs<HarnessIntent.Internal.SetEnabled>(library.sent.single())
        assertEquals(false to 4L, enabled.isEnabled to enabled.expectedRevision)
        assertEquals(HarnessLibraryAction.OpenDetail(harness.id.value), opened.await())
    }

    @Test
    fun `failing code shows diagnostics keeps the draft and saves nothing`() = runTest {
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
        val first = assertIs<HarnessIntent.Internal.Update>(library.sent.single())
        assertEquals(4L, first.expectedRevision)
        assertEquals(rule.copy(text = "Use only tokens"), (first.change as HarnessChange.PutItem).item)
        assertTrue(screen.states.value.isConflict)

        library.outcome = LibraryOutcome.Committed(HarnessOutput.Updated(request(), harness.id))
        screen.intent(HarnessItemIntent.Overwrite)
        runCurrent()
        assertEquals(5L, (library.sent.last() as HarnessIntent.Internal.Update).expectedRevision)
        assertTrue(!screen.states.value.isDirty && !screen.states.value.isConflict)
    }

    @Test
    fun `dismissing a conflict keeps the draft and saves nothing`() = runTest {
        val library = RecordingLibrary(ready)
        library.outcome = LibraryOutcome.Rejected(HarnessRejection.Conflict)
        val model = itemModel(library, rule)
        val screen = subscribe(model.store)
        screen.intent(HarnessItemIntent.ChangeText("Use only tokens"))
        screen.intent(HarnessItemIntent.Save)
        runCurrent()
        assertTrue(screen.states.value.isConflict)

        screen.intent(HarnessItemIntent.DismissConflict)
        runCurrent()
        val state = screen.states.value
        assertTrue(!state.isConflict && state.isDirty && !state.isSaving)
        assertEquals("Use only tokens", state.text)
        assertEquals(1, library.sent.size)
    }

    @Test
    fun `typing continues during a save and keeps the newer text as a draft of the saved revision`() = runTest {
        val library = RecordingLibrary(ready)
        val saving = CompletableDeferred<Unit>()
        library.submitted = saving
        val drafts = HarnessDrafts()
        val model = itemModel(library, rule, drafts)
        val screen = subscribe(model.store)
        screen.intent(HarnessItemIntent.ChangeText("Use only tokens"))
        screen.intent(HarnessItemIntent.Save)
        runCurrent()
        assertTrue(screen.states.value.isSaving)

        screen.intent(HarnessItemIntent.ChangeText("Use only design tokens"))
        runCurrent()
        assertEquals("Use only design tokens", screen.states.value.text, "the keystroke is not queued behind the save")
        saving.complete(Unit)
        runCurrent()
        val state = screen.states.value
        assertTrue(!state.isSaving && state.isDirty)
        assertEquals("Use only design tokens", state.text)
        val saved = assertIs<HarnessIntent.Internal.Update>(library.sent.single())
        assertEquals(rule.copy(text = "Use only tokens"), (saved.change as HarnessChange.PutItem).item)

        library.submitted = null
        library.state.value = ready.copy(
            harnesses = ready.harnesses.map { it.copy(harness = it.harness.copy(revision = 5)) },
        )
        screen.intent(HarnessItemIntent.Save)
        runCurrent()
        assertEquals(5L, (library.sent.last() as HarnessIntent.Internal.Update).expectedRevision)
        assertTrue(!screen.states.value.isDirty)
        assertEquals(null, drafts.get(ItemDraftKey(harness.id, rule.id)))
    }

    @Test
    fun `diagnostics of a save do not mark text typed while it compiled`() = runTest {
        val library = RecordingLibrary(ready)
        val compiling = CompletableDeferred<Unit>()
        val host = EditorHost(
            HarnessCompilationResult.Failure(
                listOf(HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, "Unresolved", 1, 3)),
            ),
            compiling,
        )
        val model = HarnessItemModel(
            harness.id.value,
            script.id.value,
            library,
            HarnessItemEditing(library, HarnessCodeChecks(host) { it }, HarnessDrafts()),
            storeFactory(),
            backgroundScope,
        )
        val screen = subscribe(model.store)
        screen.intent(HarnessItemIntent.ChangeText("broken("))
        screen.intent(HarnessItemIntent.Save)
        runCurrent()
        screen.intent(HarnessItemIntent.ChangeText("hooks.toString()"))
        runCurrent()
        compiling.complete(Unit)
        runCurrent()

        val state = screen.states.value
        assertTrue(!state.isSaving && state.isDirty)
        assertEquals("hooks.toString()", state.text)
        assertTrue(state.diagnostics.isEmpty(), "the diagnostics belong to the text that was saved")
    }

    @Test
    fun `discarding waits for the save in flight`() = runTest {
        val library = RecordingLibrary(ready)
        val saving = CompletableDeferred<Unit>()
        library.submitted = saving
        val drafts = HarnessDrafts()
        val model = itemModel(library, rule, drafts)
        val screen = subscribe(model.store)
        screen.intent(HarnessItemIntent.ChangeText("Use only tokens"))
        screen.intent(HarnessItemIntent.Save)
        runCurrent()
        screen.intent(HarnessItemIntent.Discard)
        runCurrent()

        assertTrue(screen.states.value.isSaving && screen.states.value.isDirty)
        assertEquals("Use only tokens", screen.states.value.text)
        assertEquals("Use only tokens", drafts.get(ItemDraftKey(harness.id, rule.id))?.text)
        saving.complete(Unit)
        runCurrent()
    }

    @Test
    fun `drafts of deleted items and harnesses are dropped`() = runTest {
        val library = RecordingLibrary(ready)
        val drafts = HarnessDrafts()
        val editor = subscribe(itemModel(library, rule, drafts).store)
        editor.intent(HarnessItemIntent.ChangeText("Use only tokens"))
        runCurrent()
        val other = subscribe(itemModel(library, script, drafts).store)
        other.intent(HarnessItemIntent.ChangeText("hooks.hashCode()"))
        runCurrent()

        library.state.value = ready.copy(
            harnesses = ready.harnesses.map { it.copy(harness = it.harness.copy(items = listOf(script))) },
        )
        runCurrent()
        assertEquals(PhaseUi.NotFound, editor.states.value.phase)
        assertEquals(null, drafts.get(ItemDraftKey(harness.id, rule.id)))
        assertEquals("hooks.hashCode()", drafts.get(ItemDraftKey(harness.id, script.id))?.text)

        library.state.value = ready.copy(harnesses = emptyList())
        runCurrent()
        assertEquals(null, drafts.get(ItemDraftKey(harness.id, script.id)))
    }

    @Test
    fun `template placeholders that are not argument names are rejected before saving`() = runTest {
        val template = HarnessItem.Template(ItemId("ask"), ItemName("ask"), "Asks", "Explain {{topic}}", setOf("topic"))
        val library = RecordingLibrary(
            ready.copy(harnesses = listOf(HarnessEntry(harness.copy(items = listOf(template), revision = 4)))),
        )
        val model = itemModel(library, template)
        val screen = subscribe(model.store)
        listOf("Explain {{Topic}}", "Explain {{ topic }}", "Explain {{topic").forEach { body ->
            screen.intent(HarnessItemIntent.ChangeText(body))
            screen.intent(HarnessItemIntent.Save)
            runCurrent()
            assertEquals(ItemErrorUi.InvalidPlaceholder, screen.states.value.error, body)
            assertTrue(library.sent.isEmpty(), body)
        }

        screen.intent(HarnessItemIntent.ChangeText("Explain {{topic}} to {{reader}}"))
        screen.intent(HarnessItemIntent.Save)
        runCurrent()
        val saved = (assertIs<HarnessIntent.Internal.Update>(library.sent.single()).change as HarnessChange.PutItem)
        assertEquals(setOf("topic", "reader"), (saved.item as HarnessItem.Template).arguments)
        assertEquals(null, screen.states.value.error)
    }

    @Test
    fun `library retries a failed load through the public reload`() = runTest {
        val library = RecordingLibrary(HarnessState.Failed())
        val model = HarnessLibraryModel(library, storeFactory(), backgroundScope)
        val screen = subscribe(model.store)
        assertEquals(PhaseUi.Failed, screen.states.value.phase)

        screen.intent(HarnessLibraryIntent.Reload)
        runCurrent()
        assertIs<HarnessIntent.Public.Reload>(library.sent.single())
        assertTrue(!screen.states.value.isSaveFailed)
    }
}

private fun TestScope.itemModel(
    library: RecordingLibrary,
    item: HarnessItem,
    drafts: HarnessDrafts = HarnessDrafts(),
) = HarnessItemModel(
    harness.id.value,
    item.id.value,
    library,
    HarnessItemEditing(library, HarnessCodeChecks(EditorHost(null)) { it }, drafts),
    storeFactory(),
    backgroundScope,
)

private class EditorHost(
    private val result: HarnessCompilationResult?,
    private val compiling: CompletableDeferred<Unit>? = null,
) : HarnessScriptHost {
    val requests = mutableListOf<HarnessCompilationRequest>()
    override val isAvailable = true

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
        requests += request
        compiling?.await()
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
