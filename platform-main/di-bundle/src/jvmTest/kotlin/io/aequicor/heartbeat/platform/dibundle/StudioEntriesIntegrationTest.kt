package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.di.AiStudioGraph
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionViews
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pro.respawn.flowmvi.dsl.collect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Resolves the actual screen model through Metro, including the feature-scoped entry service. */
@ContributesTo(AiStudioScope::class)
interface StudioEntryTestAccessors {
    val model: AiStudioModel
    val backend: StudioBackend
    val entries: StudioEntries
}

/** The same session view must reach both the profile recorder and the child studio graph. */
@ContributesTo(ProfileScope::class)
interface StudioHistoryTestAccessors {
    val sessionViews: StudioSessionViews
}

@OptIn(ExperimentalCoroutinesApi::class)
class StudioEntriesIntegrationTest {
    @Test
    fun `real studio graph delivers enabled research entry and observes disabling it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val flags = (app as TestToggleAccessors).toggleControl
            flags.setOverride(StudioEngineRuntime, true)
            flags.setOverride(KoogEngineEnabled, true)
            flags.setOverride(ResearchChatEnabled, true)
            val profile = app.profileSessions.open(ProfileId("studio-entry"))
            val scope = app.scopes.child(profile.graph.scope, "studio-entry")
            val graph = (profile.graph as AiStudioGraph.Factory).createAiStudio(scope)
            val access = graph as StudioEntryTestAccessors
            assertSame((profile.graph as StudioHistoryTestAccessors).sessionViews, access.backend.sessionViews())
            val model = access.model
            val observed = MutableStateFlow<AiStudioScreenState?>(null)
            backgroundScope.launch {
                model.store.collect { states.collect { observed.value = it } }
            }
            assertTrue(observed.filterNotNull().first { it.isResearchEnabled }.isResearchEnabled)
            flags.setOverride(ResearchChatEnabled, false)
            assertFalse(observed.filterNotNull().first { !it.isResearchEnabled }.isResearchEnabled)
        } finally {
            (app.appScope as OwnedScope).close()
            app.appScope.coroutineScope.coroutineContext[Job]?.join()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
}
