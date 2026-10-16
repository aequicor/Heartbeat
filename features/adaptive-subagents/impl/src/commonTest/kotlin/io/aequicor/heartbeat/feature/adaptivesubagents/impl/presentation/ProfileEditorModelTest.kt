package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ProfileSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ProfileVariant
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Traits
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.LibrarySpecMachine
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.TestDispatchers
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.Directory
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.DirectoryModel
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.target
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private typealias EditorProvider = Provider<ProfileEditorState, ProfileEditorIntent, ProfileEditorAction>

class ProfileEditorModelTest {
    private val opus = target("claude", "api", "claude-opus-4-1")
    private val key = ModelKey.of(opus)
    private val stored = ModelProfile(
        key,
        "Opus",
        "Deep",
        tags = setOf("coding"),
        base = Traits(Ratings(5, 2, 2), strengths = listOf("Reasoning", "Reviews")),
        variants = listOf(ProfileVariant("low", Traits(Ratings(4, 4, 3)))),
        source = ProfileSource.Research,
        sources = listOf("https://example.com"),
        updatedAt = Instant.fromEpochSeconds(1),
        revision = 3,
    )

    @Test
    fun `the draft is filled from the stored description and catalog efforts`() = runTest {
        val editor = Fixture(this, ModelProfilesState.Ready(listOf(stored))).subscribe().states.value
        assertTrue(editor.isLoaded && editor.isDescribed && editor.isResearched)
        assertEquals("Deep" to "coding", editor.summary to editor.tags)
        assertEquals("Reasoning\nReviews", editor.base.strengths)
        assertEquals(listOf("low", "high"), editor.efforts)
        assertEquals(setOf("low"), editor.variants.keys)
        assertEquals("Claude", editor.engine)
    }

    @Test
    fun `a saved edit replaces the description by hand and closes the editor`() = runTest {
        val fixture = Fixture(this, ModelProfilesState.Ready(listOf(stored)))
        val screen = fixture.subscribe()
        screen.intent(ProfileEditorIntent.ChangeSummary("Слабее на коротких задачах"))
        screen.intent(ProfileEditorIntent.SelectConfiguration("high"))
        screen.intent(ProfileEditorIntent.SetSeparate(true))
        screen.intent(ProfileEditorIntent.ChangeRating(AxisUi.Speed, 1))
        screen.intent(ProfileEditorIntent.SelectConfiguration("low"))
        screen.intent(ProfileEditorIntent.SetSeparate(false))
        screen.intent(ProfileEditorIntent.Save)
        runCurrent()
        val saved = (fixture.machine.state.value as ModelProfilesState.Ready).profileOf(key)
        assertEquals(4L, saved?.revision)
        assertEquals(ProfileSource.Manual, saved?.source)
        assertEquals("Слабее на коротких задачах", saved?.summary)
        assertEquals(listOf("high"), saved?.variants?.map { it.effort })
        assertEquals(Ratings(5, 1, 2), saved?.traitsFor("high")?.ratings)
        assertEquals(listOf("https://example.com"), saved?.sources)
        assertEquals(1, fixture.closes)
    }

    @Test
    fun `a conflicting or invalid save keeps the editor open with the reason`() = runTest {
        val fixture = Fixture(this, ModelProfilesState.Ready(listOf(stored)))
        val screen = fixture.subscribe()
        fixture.machine.state.value = ModelProfilesState.Ready(listOf(stored.copy(revision = 4)))
        screen.intent(ProfileEditorIntent.Save)
        runCurrent()
        assertEquals(EditorErrorUi.Conflict, screen.states.value.error)
        assertEquals(0, fixture.closes)
        screen.intent(ProfileEditorIntent.DismissError)
        runCurrent()
        assertNull(screen.states.value.error)
    }

    @Test
    fun `a new description is saved for an undescribed model and delete removes one`() = runTest {
        val fixture = Fixture(this, ModelProfilesState.Ready())
        val screen = fixture.subscribe()
        assertEquals("Opus", screen.states.value.title)
        screen.intent(ProfileEditorIntent.ChangeTags("Coding, Quick-Edits"))
        screen.intent(ProfileEditorIntent.Save)
        runCurrent()
        val saved = (fixture.machine.state.value as ModelProfilesState.Ready).profileOf(key)
        assertEquals(setOf("coding", "quick-edits"), saved?.tags)
        screen.intent(ProfileEditorIntent.Delete)
        runCurrent()
        assertNull((fixture.machine.state.value as ModelProfilesState.Ready).profileOf(key))
        assertEquals(2, fixture.closes)
    }

    private inner class Fixture(private val scope: TestScope, initial: ModelProfilesState) {
        val machine = LibrarySpecMachine(initial)
        var closes = 0
        private val directory = Directory(
            listOf(DirectoryModel(opus, "Opus", "Claude", "Claude · Work", listOf("low", "high"), "high")),
        )
        private val model = ProfileEditorModel(
            machine,
            key,
            flowOf(directory),
            { closes++ },
            HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
            scope.backgroundScope,
        )

        suspend fun subscribe(): EditorProvider {
            val provider = CompletableDeferred<EditorProvider>()
            scope.backgroundScope.launch {
                model.store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
        }
    }
}
