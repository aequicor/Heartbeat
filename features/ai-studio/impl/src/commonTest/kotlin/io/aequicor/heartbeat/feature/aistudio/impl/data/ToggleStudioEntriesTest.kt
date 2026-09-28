package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ToggleStudioEntriesTest {
    private val isEnabled = MutableStateFlow(true)
    private val sessions = FakeSessions()
    private val isSearchEnabled = MutableStateFlow(false)
    private val entries = ToggleStudioEntries(FakeToggles(isEnabled, isSearchEnabled), sessions)

    @Test
    fun `connections are offered only with the toggle on and a profile active`() = runTest {
        assertEquals(false, entries.showsConnections.first())
        sessions.active.value = ProfileSession(ProfileId("p1"), UnusedGraph)
        assertEquals(true, entries.showsConnections.first())
        isEnabled.value = false
        assertEquals(false, entries.showsConnections.first())
    }

    @Test
    fun `profile settings are offered only with search tools on and a profile active`() = runTest {
        sessions.active.value = ProfileSession(ProfileId("p1"), UnusedGraph)
        assertEquals(false, entries.showsProfileSettings.first())
        isSearchEnabled.value = true
        assertEquals(true, entries.showsProfileSettings.first())
        sessions.active.value = null
        assertEquals(false, entries.showsProfileSettings.first())
    }
}

private class FakeToggles(private val connections: Flow<Boolean>, private val search: Flow<Boolean>) : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        val flow = when (toggle) {
            EngineConnectionsEnabled -> connections
            SearchEngineTools -> search
            else -> error("unexpected toggle ${toggle.key}")
        }
        // Both toggles read here are Boolean flags checked above.
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
