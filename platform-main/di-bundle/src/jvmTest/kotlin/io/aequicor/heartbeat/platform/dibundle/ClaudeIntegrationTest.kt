package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeIntegrationTest {
    @Test
    fun `profile contributes a lazy Claude registration and disabled toggle`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val profile = app.profileSessions.open(ProfileId("claude-test"))
            val engines = (profile.graph as AiEngineAccess).engineRegistrations
            val claude = engines.single { it.descriptor.id == ClaudeEngine.Id }
            assertFalse(claude.factory.isInitialized())
            assertEquals(ClaudeEngine.AuthOwner, claude.authOwner)
            assertNull(claude.sessionSources.single().discovery)
            val toggles = app as TestToggleAccessors
            assertTrue(ClaudeEngine.Enabled in toggles.toggleControl.registered)
            assertFalse(toggles.featureToggles.get(ClaudeEngine.Enabled))
        } finally {
            (app.appScope as OwnedScope).close()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
}
