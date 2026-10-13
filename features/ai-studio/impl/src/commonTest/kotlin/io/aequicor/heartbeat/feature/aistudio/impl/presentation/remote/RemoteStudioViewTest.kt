package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewFrame
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewMirror
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
        val fixture = AiStudioModelTest.Fixture(scope, ready)
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
    fun `a host gets a snapshot, then deltas that acknowledge its intents`() = runTest {
        val harness = Harness(this, ready)
        val frames = collect(harness)
        val mirror = StudioViewMirror()

        val snapshot = StudioViewJson.decodeFromJsonElement(StudioViewFrame.serializer(), frames.receive())
        assertIs<StudioViewFrame.Snapshot>(snapshot)
        mirror.apply(snapshot)

        val reply = harness.view.apply(listOf(intent(1, AiStudioScreenIntent.ToggleSearch)))
        assertEquals(StudioRemoteReply.Applied(1, emptyList()), reply)
        advanceTimeBy(200.milliseconds)
        runCurrent()

        var state = mirror.apply(StudioViewJson.decodeFromJsonElement(StudioViewFrame.serializer(), frames.receive()))
        while (state.remoteSeq < 1) {
            state = mirror.apply(StudioViewJson.decodeFromJsonElement(StudioViewFrame.serializer(), frames.receive()))
        }
        assertTrue(state.sidebar.isSearchVisible)
    }

    @Test
    fun `native surfaces, forged markers, repeats and garbage are refused or skipped`() = runTest {
        val harness = Harness(this, ready)

        val reply = harness.view.apply(
            listOf(
                intent(2, AiStudioScreenIntent.AddProject(0)),
                intent(1, AiStudioScreenIntent.ToggleSearch),
                intent(3, AiStudioScreenIntent.RemoteApplied(99)),
                StudioRemoteIntent(4, JsonPrimitive("not an intent")),
            ),
        )
        val again = harness.view.apply(listOf(intent(4, AiStudioScreenIntent.ToggleSearch)))

        assertEquals(StudioRemoteReply.Applied(4, listOf(2L, 3L, 4L)), reply)
        assertEquals(StudioRemoteReply.Applied(4, emptyList()), again)
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
}
