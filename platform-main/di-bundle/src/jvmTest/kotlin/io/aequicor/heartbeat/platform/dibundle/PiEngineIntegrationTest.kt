package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiEngineIntegrationTest {
    @Test
    fun bundledDefaultIsLazyAndRespectsToggleWithoutCredentials() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val session = app.profileSessions.open(ProfileId("pi-default-test"))
            val engines = session.graph as AiEngineAccessors
            val registration = engines.engineRegistrations.single { it.descriptor.id == PiEngineId }
            assertFalse(registration.factory.isInitialized())
            assertTrue(registration.descriptor.isDefault)
            val expected = PiEngineId.takeIf {
                app.platformInfo.host in setOf(
                    HostPlatform.Windows,
                    HostPlatform.MacOs,
                )
            }
            assertEquals(expected, engines.engineDefaults.preferred())
            assertFalse(registration.factory.isInitialized())
            (app as TestToggleAccessors).toggleControl.setOverride(PiEnabled, false)
            assertNull(engines.engineDefaults.preferred())
        } finally {
            (app.appScope as OwnedScope).close()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
}
