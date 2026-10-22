package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileContent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileLinks
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.isAllowedRemotely
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StudioFilePreviewModelTest {
    private val files = Files()

    @Test
    fun `opening reads the file and a line opens markdown as source`() = runTest {
        files.texts["/p/plan.md"] = "# Plan"
        val model = model()
        val screen = model.subscribe(this)
        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest("/p/plan.md", "plan.md")))
        runCurrent()
        val state = screen.states.value
        assertEquals(1L, model.opening.value)
        assertEquals("# Plan", assertIs<StudioFileView.Text>(state.content).text)
        assertTrue(state.isMarkdown)
        assertFalse(state.isSource)

        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest("/p/plan.md", "plan.md:3", 3)))
        runCurrent()
        assertEquals(2L, model.opening.value)
        assertTrue(screen.states.value.isSource)
    }

    @Test
    fun `unresolved links say the file is unavailable and close hides the preview`() = runTest {
        val model = model()
        val screen = model.subscribe(this)
        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest(null, "missing.kt")))
        runCurrent()
        assertEquals(StudioFileView.Unavailable, screen.states.value.content)
        assertEquals("missing.kt", screen.states.value.name)

        model.store.intent(StudioFilePreviewIntent.Close)
        runCurrent()
        assertNull(model.opening.value)
    }

    @Test
    fun `links inside a file resolve against its folder`() = runTest {
        files.texts["/p/docs/plan.md"] = "[x](notes.md)"
        files.texts["/p/docs/notes.md"] = "notes"
        val model = model()
        val screen = model.subscribe(this)
        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest("/p/docs/plan.md", "docs/plan.md")))
        model.store.intent(StudioFilePreviewIntent.OpenLinked("notes.md", null))
        runCurrent()
        assertEquals("/p/docs/notes.md", screen.states.value.path)
        assertEquals("notes", assertIs<StudioFileView.Text>(screen.states.value.content).text)
    }

    @Test
    fun `a watched file is read again when it changes`() = runTest {
        files.texts["/p/plan.md"] = "v1"
        val model = model()
        val screen = model.subscribe(this)
        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest("/p/plan.md", "plan.md")))
        runCurrent()
        files.texts["/p/plan.md"] = "v2"
        files.modified["/p/plan.md"] = 2
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals("v2", assertIs<StudioFileView.Text>(screen.states.value.content).text)
    }

    @Test
    fun `a file that appears or disappears while watched is shown again`() = runTest {
        val model = model()
        val screen = model.subscribe(this)
        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest("/p/plan.md", "plan.md")))
        runCurrent()
        assertEquals(StudioFileView.Unavailable, screen.states.value.content)

        files.texts["/p/plan.md"] = "created"
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals("created", assertIs<StudioFileView.Text>(screen.states.value.content).text)

        files.texts.remove("/p/plan.md")
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(StudioFileView.Unavailable, screen.states.value.content)
    }

    @Test
    fun `a file that is not shown as text is not read again until it changes`() = runTest {
        files.texts["/p/big.log"] = "big"
        files.tooLarge += "/p/big.log"
        val model = model()
        val screen = model.subscribe(this)
        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest("/p/big.log", "big.log")))
        runCurrent()
        assertEquals(StudioFileView.TooLarge, screen.states.value.content)
        advanceTimeBy(7.seconds)
        runCurrent()
        assertEquals(1, files.reads)

        files.modified["/p/big.log"] = 5
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(2, files.reads)
    }

    @Test
    fun `links inside a file in a windows drive root keep the root`() = runTest {
        files.texts["C:\\plan.md"] = "[x](notes.md)"
        files.texts["C:\\notes.md"] = "notes"
        val model = model()
        val screen = model.subscribe(this)
        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest("C:\\plan.md", "plan.md")))
        model.store.intent(StudioFilePreviewIntent.OpenLinked("notes.md", null))
        runCurrent()
        assertEquals("C:\\notes.md", screen.states.value.path)
    }

    @Test
    fun `links inside a file in the root resolve against the root`() = runTest {
        files.texts["/plan.md"] = "[x](notes.md)"
        files.texts["/notes.md"] = "notes"
        val model = model()
        val screen = model.subscribe(this)
        model.store.intent(StudioFilePreviewIntent.Open(StudioFileRequest("/plan.md", "plan.md")))
        model.store.intent(StudioFilePreviewIntent.OpenLinked("notes.md", null))
        runCurrent()
        assertEquals("notes", assertIs<StudioFileView.Text>(screen.states.value.content).text)
    }

    @Test
    fun `chat file links resolve against the pane chat and never come from a remote view`() = runTest {
        files.isAvailable.value = true
        val state = AiStudioState.Ready(
            panes = listOf(StudioPane(0, sessionId = "chat", projectId = "project")),
            focusedPaneId = 0,
            settings = DefaultRunSettings,
        )
        val fixture = AiStudioModelTest.Fixture(this, state, fileLinks = StudioFileLinkOpener(files))
        val screen = fixture.subscribe()
        assertTrue(screen.states.value.isFilePreviewEnabled)
        val opened = CompletableDeferred<StudioFileRequest>()
        backgroundScope.launch { opened.complete(fixture.fileLinks.navigation.first()) }
        runCurrent()
        fixture.model.store.intent(AiStudioScreenIntent.OpenFileLink(0, "src/a.kt", 4))
        runCurrent()
        assertEquals(StudioFileRequest("/chat/src/a.kt", "src/a.kt", 4), opened.await())
        assertFalse(isAllowedRemotely(AiStudioScreenIntent.OpenFileLink(0, "src/a.kt")))
    }

    private fun TestScope.model() = StudioFilePreviewModel(
        HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(testScheduler))),
        files,
        TestScopeHandle(backgroundScope),
    )

    private suspend fun StudioFilePreviewModel.subscribe(
        scope: TestScope,
    ): Provider<StudioFilePreviewState, StudioFilePreviewIntent, StudioFilePreviewAction> {
        val provider =
            CompletableDeferred<Provider<StudioFilePreviewState, StudioFilePreviewIntent, StudioFilePreviewAction>>()
        scope.backgroundScope.launch {
            store.collect {
                provider.complete(this)
                awaitCancellation()
            }
        }
        scope.runCurrent()
        return provider.await()
    }

    private class Files : StudioFileLinks {
        val texts = mutableMapOf<String, String>()
        val tooLarge = mutableSetOf<String>()
        var reads = 0
        val modified = mutableMapOf<String, Long>()
        val isAvailable = MutableStateFlow(false)
        override val availability: Flow<Boolean> = isAvailable

        override suspend fun resolve(path: String, sessionId: String?, projectId: String?): String? =
            sessionId?.let { "/$it/$path" }

        override suspend fun resolveIn(path: String, directory: String): String? =
            if (directory.endsWith('/') || directory.endsWith('\\')) "$directory$path" else "$directory/$path"

        override suspend fun read(path: String): StudioFileContent {
            reads++
            return when {
                path in tooLarge -> StudioFileContent.TooLarge(2)

                else -> texts[path]?.let { StudioFileContent.Text(it, modified[path] ?: 1) }
                    ?: StudioFileContent.Unavailable
            }
        }

        override suspend fun modifiedAt(path: String): Long? = if (path in texts) modified[path] ?: 1 else null
    }
}
