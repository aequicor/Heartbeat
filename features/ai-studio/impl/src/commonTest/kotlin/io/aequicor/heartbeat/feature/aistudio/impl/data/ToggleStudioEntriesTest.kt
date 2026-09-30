package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ToggleStudioEntriesTest {
    private val enabled = MutableStateFlow(true)
    private val sessions = FakeSessions()
    private val searchEnabled = MutableStateFlow(false)
    private val entries = ToggleStudioEntries(FakeToggles(enabled, searchEnabled), sessions, Unused)

    @Test
    fun `research entry observes all prerequisites and disappears on disable`() = runTest {
        val research = MutableStateFlow(true)
        val runtime = MutableStateFlow(true)
        val koog = MutableStateFlow(true)
        val gated = ToggleStudioEntries(
            FakeToggles(enabled, searchEnabled, research, runtime, koog),
            sessions,
            Unused,
        )
        assertEquals(false, gated.showsResearch.first())
        sessions.active.value = ProfileSession(ProfileId("p1"), UnusedGraph)
        assertEquals(true, gated.showsResearch.first())
        research.value = false
        assertEquals(false, gated.showsResearch.first())
        research.value = true
        runtime.value = false
        assertEquals(false, gated.showsResearch.first())
        runtime.value = true
        koog.value = false
        assertEquals(false, gated.showsResearch.first())
    }

    @Test
    fun `connections are offered only with the toggle on and a profile active`() = runTest {
        assertEquals(false, entries.showsConnections.first())
        sessions.active.value = ProfileSession(ProfileId("p1"), UnusedGraph)
        assertEquals(true, entries.showsConnections.first())
        enabled.value = false
        assertEquals(false, entries.showsConnections.first())
    }

    @Test
    fun `profile settings are offered only with search tools on and a profile active`() = runTest {
        sessions.active.value = ProfileSession(ProfileId("p1"), UnusedGraph)
        assertEquals(false, entries.showsProfileSettings.first())
        searchEnabled.value = true
        assertEquals(true, entries.showsProfileSettings.first())
        sessions.active.value = null
        assertEquals(false, entries.showsProfileSettings.first())
    }
}

private class FakeToggles(
    private val connections: Flow<Boolean>,
    private val search: Flow<Boolean>,
    private val research: Flow<Boolean> = flowOf(false),
    private val runtime: Flow<Boolean> = flowOf(false),
    private val koog: Flow<Boolean> = flowOf(false),
) : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        val flow = when (toggle) {
            EngineConnectionsEnabled -> connections
            SearchEngineTools -> search
            ResearchChatEnabled -> research
            StudioEngineRuntime -> runtime
            KoogEngineEnabled -> koog
            is FeatureToggle.Flag, is FeatureToggle.Choice -> flowOf(toggle.default)
        }
        // All toggles read here are Boolean flags checked above.
        @Suppress("UNCHECKED_CAST")
        return flow as Flow<T>
    }

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private class FakeSessions : ProfileSessions {
    override val active = MutableStateFlow<ProfileSession?>(null)

    override suspend fun restore(): ProfileSession? = active.value

    override suspend fun open(id: ProfileId): ProfileSession = error("unused")

    override suspend fun close() {
        active.value = null
    }
}

private object UnusedGraph : ProfileGraph {
    override val scope get() = error("unused")
    override val sharedScopes get() = error("unused")
}

private val Unused = lazy<StudioQuestionBridge> { error("unused") }
