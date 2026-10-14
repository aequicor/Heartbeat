package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewFrame
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewMirror
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RemoteStudioViewTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "s-facade")),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )

    private class Harness(scope: TestScope, ready: AiStudioState) {
        var isReleased = false
        val fixture = AiStudioModelTest.Fixture(scope, ready, host = StudioScreenHost.Remote)
        val view = RemoteStudioView(
            id = "view-1",
            release = { isReleased = true },
            model = fixture.model,
            encoding = StandardTestDispatcher(scope.testScheduler),
            frameInterval = 100.milliseconds,
        )
    }

    private fun intent(seq: Long, intent: AiStudioScreenIntent) =
        StudioRemoteIntent(seq, StudioViewJson.encodeToJsonElement(AiStudioScreenIntent.serializer(), intent))

    private fun TestScope.collect(harness: Harness): Channel<JsonObject> {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        backgroundScope.launch { harness.view.frames(onDetached = {}).collect { frames.send(it) } }
        runCurrent()
        return frames
    }

    @Test
    fun `acceptance during reconnect grace clears the pending draft`() = runTest {
        checkSubmissionAfterDetach(isAccepted = true)
    }

    @Test
    fun `rejection during reconnect grace restores a usable failed draft`() = runTest {
        checkSubmissionAfterDetach(isAccepted = false)
    }

    private suspend fun TestScope.checkSubmissionAfterDetach(isAccepted: Boolean) {
        val harness = Harness(this, ready)
        val connection = backgroundScope.launch { harness.view.frames(onDetached = {}).collect {} }
        runCurrent()
        harness.view.apply(
            harness.view.generation,
            listOf(
                intent(1, AiStudioScreenIntent.Suggestions.DraftChanged(0, "Pending prompt")),
                intent(2, AiStudioScreenIntent.Submit(0)),
            ),
        )
        runCurrent()
        val sent = harness.fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().single()
        connection.cancelAndJoin()
        advanceTimeBy(1_100.milliseconds)
        runCurrent()
        val output = if (isAccepted) {
            AiStudioOutput.SubmitAccepted(0, sent.submissionId, "s-facade")
        } else {
            AiStudioOutput.SubmitRejected(0, sent.submissionId, "s-facade")
        }
        harness.fixture.machine.outputs.emit(output)
        runCurrent()

        val frames = collect(harness)
        val snapshot = StudioViewJson.decodeFromJsonElement(StudioViewFrame.serializer(), frames.receive())
        val screen = StudioViewMirror().apply(snapshot)
        assertTrue(screen.submissions.isEmpty())
        assertEquals(if (isAccepted) "" else "Pending prompt", screen.draft(0))
        assertEquals(!isAccepted, 0 in screen.failedPanes)
    }

    @Test
    fun `queued submission keeps its admitted browser after a new owner activates`() = runTest {
        val harness = Harness(this, ready)
        collect(harness)
        val first = object : StudioChatEnvironment {
            override fun bind(session: SessionRef): AutoCloseable = AutoCloseable { }
        }
        harness.view.activate(harness.view.reserveAttachment(), "a", first)
        harness.view.apply(
            harness.view.generation,
            listOf(
                intent(1, AiStudioScreenIntent.Suggestions.DraftChanged(0, "From A")),
                intent(2, AiStudioScreenIntent.Submit(0)),
            ),
            "a",
        )
        harness.view.activate(harness.view.reserveAttachment(), "b", null)
        assertEquals(
            StudioRemoteReply.UnknownView,
            harness.view.apply(
                harness.view.generation,
                listOf(intent(3, AiStudioScreenIntent.Submit(0))),
                "a",
            ),
        )
        runCurrent()
        val submitted = harness.fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().single()
        assertSame(first, submitted.environment)
    }

    @Test
    fun `a host gets a snapshot, then deltas that acknowledge its intents`() = runTest {
        val harness = Harness(this, ready)
        val frames = collect(harness)
        val mirror = StudioViewMirror()

        val snapshot = StudioViewJson.decodeFromJsonElement(StudioViewFrame.serializer(), frames.receive())
        assertIs<StudioViewFrame.Snapshot>(snapshot)
        mirror.apply(snapshot)

        val reply = harness.view.apply(
            harness.view.generation,
            listOf(
                intent(1, AiStudioScreenIntent.ToggleSearch),
                intent(2, AiStudioScreenIntent.ToggleProjectSessions("p-heartbeat")),
                intent(3, AiStudioScreenIntent.ToggleNoProjectSection),
            ),
        )
        assertEquals(StudioRemoteReply.Applied(3, emptyList()), reply)
        advanceTimeBy(200.milliseconds)
        runCurrent()

        var state = mirror.apply(StudioViewJson.decodeFromJsonElement(StudioViewFrame.serializer(), frames.receive()))
        while (state.remoteSeq < 3) {
            state = mirror.apply(StudioViewJson.decodeFromJsonElement(StudioViewFrame.serializer(), frames.receive()))
        }
        assertTrue(state.sidebar.isSearchVisible)
        assertEquals(setOf("p-heartbeat"), state.sidebar.expandedProjectSessions)
        assertEquals(false, state.sidebar.isNoProjectExpanded)
    }

    @Test
    fun `native surfaces, forged markers, repeats and garbage are refused or skipped`() = runTest {
        val harness = Harness(this, ready)

        val reply = harness.view.apply(
            harness.view.generation,
            listOf(
                intent(2, AiStudioScreenIntent.AddProject(0)),
                intent(1, AiStudioScreenIntent.ToggleSearch),
                intent(3, AiStudioScreenIntent.RemoteApplied(99)),
                StudioRemoteIntent(4, JsonPrimitive("not an intent")),
            ),
        )
        val again = harness.view.apply(harness.view.generation, listOf(intent(4, AiStudioScreenIntent.ToggleSearch)))

        assertEquals(StudioRemoteReply.Applied(4, listOf(2L, 3L, 4L)), reply)
        assertEquals(StudioRemoteReply.Applied(4, emptyList()), again)
    }

    @Test
    fun `remote clients cannot request native application installation`() = runTest {
        val harness = Harness(this, ready)
        val reply = harness.view.apply(
            harness.view.generation,
            listOf(intent(1, AiStudioScreenIntent.InstallAppUpdate)),
        )
        assertEquals(StudioRemoteReply.Applied(1, listOf(1L)), reply)
    }

    @Test
    fun `remote clients cannot acknowledge native inline card focus`() = runTest {
        val harness = Harness(this, ready)
        val reply = harness.view.apply(
            harness.view.generation,
            listOf(intent(1, AiStudioScreenIntent.CardFocusApplied(0, 1))),
        )
        assertEquals(StudioRemoteReply.Applied(1, listOf(1L)), reply)
    }

    @Test
    fun `a newer host detaches the older one and closing releases the scope`() = runTest {
        val harness = Harness(this, ready)
        var detached = 0
        backgroundScope.launch { harness.view.frames(onDetached = { detached++ }).collect {} }
        runCurrent()
        assertTrue(harness.view.isAttached)

        backgroundScope.launch { harness.view.frames(onDetached = { detached++ }).collect {} }
        runCurrent()
        assertEquals(1, detached)

        harness.view.close()
        runCurrent()
        assertEquals(2, detached)
        assertTrue(harness.isReleased)
    }

    @Test
    fun `a request addressed to a destroyed instance never enters the new store`() = runTest {
        val old = Harness(this, ready)
        val replacement = Harness(this, ready)
        val screen = replacement.fixture.subscribe()
        val intent = intent(1, AiStudioScreenIntent.ToggleSearch)

        assertEquals(StudioRemoteReply.UnknownView, replacement.view.apply(old.view.generation, listOf(intent)))
        assertEquals(
            StudioRemoteReply.Applied(1, emptyList()),
            replacement.view.apply(replacement.view.generation, listOf(intent)),
        )
        runCurrent()
        assertTrue(screen.states.value.sidebar.isSearchVisible)
    }
}
