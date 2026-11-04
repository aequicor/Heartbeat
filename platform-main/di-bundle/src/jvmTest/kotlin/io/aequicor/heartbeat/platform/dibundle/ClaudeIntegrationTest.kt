package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeIntegrationTest {
    @Test
    fun `profile contributes a lazy Claude registration and an enabled by default toggle`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val profile = app.profileSessions.open(ProfileId("claude-test"))
            val engines = (profile.graph as RegisteredEnginesAccess).registeredEngines.all
            val claude = engines.single { it.descriptor.id == ClaudeEngine.Id }
            assertFalse(claude.factory.isInitialized())
            assertEquals(ClaudeEngine.AuthOwner, claude.authOwner)
            assertNull(claude.sessionSources.single().discovery)
            val toggles = app as TestToggleAccessors
            assertTrue(ClaudeEngine.Enabled in toggles.toggleControl.registered)
            assertTrue(toggles.featureToggles.get(ClaudeEngine.Enabled))
        } finally {
            app.closeAndAwaitStorages()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }

    @Test
    fun `profile publishes the desktop Claude admission policy without starting a process`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val profile = app.profileSessions.open(ProfileId("claude-policy"))
            val engines = (profile.graph as RegisteredEnginesAccess).registeredEngines.all
            val registration = engines.single { it.descriptor.id == ClaudeEngine.Id }
            val policy = assertNotNull(registration.factory.value.runtimeRequirements)
            assertEquals(ClaudeEngine.Id, policy.engine)
            assertEquals("2.1.285", policy.minimumRuntime)
            assertEquals(setOf(EngineProtocolIdentity.ProbedSchema("claude-cli-stream", "1")), policy.protocols)
            assertEquals(setOf(0), policy.configurationSchemas)
            assertEquals(setOf(0), policy.historySchemas)
            assertTrue(policy.verified.isEmpty())
            assertTrue(policy.nativeTargets?.isNotEmpty() == true)
        } finally {
            app.closeAndAwaitStorages()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
}
