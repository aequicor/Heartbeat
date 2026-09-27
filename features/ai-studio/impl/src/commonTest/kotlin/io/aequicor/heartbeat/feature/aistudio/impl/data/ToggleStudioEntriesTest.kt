package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ToggleStudioEntriesTest {
    private val isEnabled = MutableStateFlow(true)
    private val sessions = FakeSessions()
    private val entries = ToggleStudioEntries(FakeToggles(isEnabled), sessions)

    @Test
    fun `connections are offered only with the toggle on and a profile active`() = runTest {
        assertEquals(false, entries.showsConnections.first())
        sessions.active.value = ProfileSession(ProfileId("p1"), UnusedGraph)
        assertEquals(true, entries.showsConnections.first())
        isEnabled.value = false
        assertEquals(false, entries.showsConnections.first())
    }
}

private class FakeToggles(private val connections: Flow<Boolean>) : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        check(toggle == EngineConnectionsEnabled) { "unexpected toggle ${toggle.key}" }
        // The only toggle read here is the Boolean connections flag checked above.
        @Suppress("UNCHECKED_CAST")
        return connections as Flow<T>
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
