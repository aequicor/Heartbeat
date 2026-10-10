package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
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
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    @Test
    fun `binding rejects foreign or unversioned credentials and ignores unknown unbinds`() = withProfile {
        val factory = engines.piRegistration().factory.value
        val binding = EngineBindingId("pi-binding")
        val foreign = assertFailsWith<EngineException> {
            factory.bind(binding, managedKey(origin = "https://example.invalid"))
        }
        assertIs<EngineFailure.Authentication>(foreign.failure)
        val unversioned = assertFailsWith<EngineException> {
            factory.bind(binding, managedKey(revision = AuthRevision.Unknown))
        }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid), unversioned.failure)
        factory.unbind(EngineBindingId("unknown"))
    }

    @Test
    fun `workspace must be an existing directory`() = withProfile {
        val directory = Files.createTempDirectory("pi-workspace").toFile()
        try {
            val file = File(directory, "file.txt").apply { writeText("x") }
            listOf(File(directory, "missing"), file).forEach { path ->
                val failure = assertFailsWith<EngineException> {
                    engines.piEngine.configureWorkspace(WorkspaceRef("workspace"), path.path)
                }
                assertEquals(EngineFailure.Request(RequestFailureReason.Invalid), failure.failure)
            }
            engines.piEngine.configureWorkspace(WorkspaceRef("workspace"), directory.path)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun managedKey(
        origin: String = "https://api.anthropic.com",
        revision: AuthRevision = AuthRevision.Known("1"),
    ) = AuthSource.ManagedKey(
        AuthSourceInfo(AuthSourceId("pi-key"), "Key", revision),
        AuthScope(ProviderId("anthropic"), EndpointOrigin(origin)),
        AuthSecretId("pi-key"),
    )

    private fun AiEngineAccessors.piRegistration(): EngineRegistration =
        engineRegistrations.single { it.descriptor.id == PiEngineId }

    private fun withProfile(block: suspend ProfileFixture.() -> Unit) = runTest {
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        try {
            val session = app.profileSessions.open(ProfileId("pi-default-test"))
            ProfileFixture(app, session.graph as AiEngineAccessors).block()
        } finally {
            app.closeAndAwaitStorages()
        }
    }

    private data class ProfileFixture(val app: TestAppGraph, val engines: AiEngineAccessors)
}
