@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `profile contributes lazy Codex registration and disabled gate prevents CLI startup`() = runTest {
        val profile = app.profileSessions.open(ProfileId("codex-test"))
        val registration = (profile.graph as CodexTestAccessors).engines.single { it.descriptor.id == CodexEngine.Id }
        assertEquals(CodexEngine.AuthOwner, registration.authOwner)
        assertTrue(CodexEngine.Enabled in (app as TestToggleAccessors).toggleControl.registered)
        val error = assertFailsWith<EngineException> {
            registration.factory.value.createRuntime(
                RuntimeIdentity(CodexEngine.Id, AuthSourceId("codex.local"), AuthRevision.Unknown),
            )
        }
        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), error.failure)
        // The installation probe would start the CLI; the disabled toggle must answer first.
        assertEquals(
            EngineAvailability.Unavailable(EngineFailure.Access(AccessFailureReason.OperationNotAllowed)),
            registration.factory.value.checkRequirements(),
        )
    }
}

@ContributesTo(ProfileScope::class)
interface CodexTestAccessors {
    val engines: Set<EngineRegistration>
}
