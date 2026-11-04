package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
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

class KimiIntegrationTest {
    @Test
    fun `profile contributes a lazy Kimi registration and an enabled by default toggle`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val profile = app.profileSessions.open(ProfileId("kimi-test"))
            val engines = (profile.graph as RegisteredEnginesAccess).registeredEngines.all
            val kimi = engines.single { it.descriptor.id == KimiEngine.Id }
            assertFalse(kimi.factory.isInitialized())
            assertEquals(KimiEngine.AuthOwner, kimi.authOwner)
            assertNull(kimi.sessionSources.single().discovery)
            val toggles = app as TestToggleAccessors
            assertTrue(KimiEngine.Enabled in toggles.toggleControl.registered)
            assertTrue(toggles.featureToggles.get(KimiEngine.Enabled))
        } finally {
            app.closeAndAwaitStorages()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }

    @Test
    fun `profile publishes the desktop Kimi admission policy without starting a process`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val profile = app.profileSessions.open(ProfileId("kimi-policy"))
            val engines = (profile.graph as RegisteredEnginesAccess).registeredEngines.all
            val registration = engines.single { it.descriptor.id == KimiEngine.Id }
            val policy = assertNotNull(registration.factory.value.runtimeRequirements)
            assertEquals(KimiEngine.Id, policy.engine)
            assertEquals("2.4.0-hb7", policy.minimumRuntime)
            assertNull(policy.maximumRuntimeExclusive)
            assertEquals(setOf(EngineProtocolIdentity.Negotiated("acp", 1)), policy.protocols)
            assertEquals(setOf(0), policy.configurationSchemas)
            assertEquals(setOf(0), policy.historySchemas)
            assertTrue(policy.verified.isEmpty())
            assertEquals(
                setOf("win32-x64", "win32-arm64", "darwin-x64", "darwin-arm64", "linux-x64"),
                policy.nativeTargets,
            )
        } finally {
            app.closeAndAwaitStorages()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
}
