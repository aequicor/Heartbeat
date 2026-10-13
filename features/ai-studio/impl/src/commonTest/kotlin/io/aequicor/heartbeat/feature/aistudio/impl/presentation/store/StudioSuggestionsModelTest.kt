package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest.Fixture
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SuggestionKindUi
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssistOrigin
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerScope
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerTrigger
import io.aequicor.heartbeat.feature.autocomplete.api.HostCommand
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class StudioSuggestionsModelTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "s-facade")),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )

    @Test
    fun `editing the token invalidates acceptance before the next debounce`() = runTest {
        val fixture = Fixture(this, ready, assists = TestComposerAssists())
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/old"))
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertEquals("/old", screen.states.value.suggestions(0)?.items?.single()?.label)

        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/new"))
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.AcceptSuggestion(0))
        runCurrent()
        assertEquals("/new", screen.states.value.draft(0))
        assertNull(screen.states.value.suggestions(0))
        assertNull(screen.states.value.caretRequest(0))

        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertEquals("/new", screen.states.value.suggestions(0)?.items?.single()?.label)
    }

    @Test
    fun `project and model changes invalidate the list without another edit`() = runTest {
        val target = EngineTarget(EngineId("pi"), EngineBindingId("b"), ModelId("first"))
        val initial = ready.copy(
            panes = listOf(StudioPane(0, projectId = "first-project")),
            settings = ready.settings.copy(modelId = target.studioModelId()),
        )
        val source = TestComposerAssists()
        val fixture = Fixture(this, initial, assists = source)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/plan"))
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertNotNull(screen.states.value.suggestions(0))

        val nextTarget = target.copy(model = ModelId("second"))
        fixture.machine.state.value = initial.copy(
            panes = listOf(StudioPane(0, projectId = "second-project")),
            settings = initial.settings.copy(modelId = nextTarget.studioModelId()),
        )
        runCurrent()
        assertNull(screen.states.value.suggestions(0))
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.AcceptSuggestion(0))
        runCurrent()
        assertEquals("/plan", screen.states.value.draft(0))
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertEquals(nextTarget, source.scopes.last().target)
        assertEquals(WorkspaceRef("second-project"), source.scopes.last().workspace)
        assertNotNull(screen.states.value.suggestions(0))
    }

    @Test
    fun `a late source result cannot publish after the draft changes`() = runTest {
        var pending: Continuation<List<ComposerSuggestion>>? = null
        val source = TestComposerAssists { trigger ->
            if (trigger.query == "old") suspendCoroutine { pending = it } else listOf(testCommand(trigger.query))
        }
        val fixture = Fixture(this, ready, assists = source)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/old"))
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertNotNull(pending)

        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/new"))
        runCurrent()
        pending?.resume(listOf(testCommand("old")))
        runCurrent()
        assertNull(screen.states.value.suggestions(0))
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertEquals("/new", screen.states.value.suggestions(0)?.items?.single()?.label)
    }

    @Test
    fun `a remote screen excludes native files while keeping command acceptance`() = runTest {
        val file = ComposerSuggestion.File(
            "file",
            "notes.md",
            "",
            ComposerAssistOrigin.Heartbeat,
            "notes.md",
            "/workspace/notes.md",
            12,
            "text/markdown",
        )
        val source = TestComposerAssists { listOf(file, testCommand("plan")) }
        val fixture = Fixture(this, ready, host = StudioScreenHost.Remote, assists = source)
        val screen = fixture.subscribe()
        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/plan"))
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertEquals(listOf(SuggestionKindUi.Command), screen.states.value.suggestions(0)?.items?.map { it.kind })

        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.AcceptSuggestion(0))
        runCurrent()
        assertEquals("/plan ", screen.states.value.draft(0))
    }
}

private class TestComposerAssists(
    private val resolve: suspend (ComposerTrigger) -> List<ComposerSuggestion> = { listOf(testCommand(it.query)) },
) : ComposerAssists {
    val scopes = mutableListOf<ComposerScope>()

    override suspend fun suggest(
        trigger: ComposerTrigger,
        scope: ComposerScope,
        hostCommands: List<HostCommand>,
    ): List<ComposerSuggestion> {
        scopes += scope
        return resolve(trigger)
    }
}

private fun testCommand(name: String): ComposerSuggestion.Command = ComposerSuggestion.Command(
    "host:$name",
    "/$name",
    "",
    ComposerAssistOrigin.Heartbeat,
    "/$name ",
)
