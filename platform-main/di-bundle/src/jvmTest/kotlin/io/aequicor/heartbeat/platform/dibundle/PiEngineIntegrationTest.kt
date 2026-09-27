package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Wiring of the bundled Pi engine; the host-specific default choice is covered by facade:impl unit tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class PiEngineIntegrationTest {
    private val persisted = PersistedProfile()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `profile keeps one Pi registration and default resolution does not construct the adapter`() = withProfile {
        val registration = engines.piRegistration()
        assertTrue(registration.descriptor.isDefault)
        engines.engineDefaults.preferred()
        assertSame(registration, engines.piRegistration())
        assertFalse(registration.factory.isInitialized())
    }

    @Test
    fun `Pi configuration and registered factory share the profile adapter`() = withProfile {
        val registration = engines.piRegistration()
        assertSame<Any>(engines.piEngine, registration.factory.value)
    }

    @Test
    fun `disabled Pi toggle removes the default on any host`() = withProfile {
        val toggles = (app as TestToggleAccessors).toggleControl
        assertTrue(toggles.registered.containsAll(listOf(AiEngines, PiEnabled)))
        toggles.setOverride(PiEnabled, false)
        assertNull(engines.engineDefaults.preferred())
        assertFalse(engines.piRegistration().factory.isInitialized())
    }

    private fun AiEngineAccessors.piRegistration(): EngineRegistration =
        engineRegistrations.single { it.descriptor.id == PiEngineId }

    private fun withProfile(block: suspend ProfileFixture.() -> Unit) = runTest {
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val session = app.profileSessions.open(ProfileId("pi-default-test"))
            ProfileFixture(app, session.graph as AiEngineAccessors).block()
        } finally {
            (app.appScope as OwnedScope).close()
        }
    }

    private class ProfileFixture(val app: TestAppGraph, val engines: AiEngineAccessors)
}
