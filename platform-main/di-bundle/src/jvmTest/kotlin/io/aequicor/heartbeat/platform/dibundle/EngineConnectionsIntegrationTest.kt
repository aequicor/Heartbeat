package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Profile accessor of the model choice read by model pickers. */
@ContributesTo(ProfileScope::class)
interface ModelSelectionsAccessor {
    val modelSelections: ModelSelections
}

@OptIn(ExperimentalCoroutinesApi::class)
class EngineConnectionsIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `settings space and wizard open in the profile tree`() = runTest {
        val session = app.profileSessions.open(ProfileId("p1"))
        val context = DefaultComponentContext(LifecycleRegistry().apply { resume() })
        val profile = (session.graph as ProfileNavigation).navigation
            .create(context, initial = listOf(EngineConnectionsRoute), name = "profile")
        profile.navigator.navigate(ConnectEngineRoute(EngineId("koog")))
        val routes = profile.stack.value.items.map { it.configuration.route }
        assertEquals(listOf(EngineConnectionsRoute, ConnectEngineRoute(EngineId("koog"))), routes)
    }

    @Test
    fun `a facade runtime and source registry in the profile replace the stand-ins`() = runTest {
        val services = (app.profileSessions.open(ProfileId("p1")).graph as EngineServicesAccessor).engineServices
        assertIs<TestEngineFacade>(services.facade)
        assertIs<TestAuthSources>(services.sources)
    }

    @Test
    fun `model choice is stored per profile`() = runTest {
        val target = EngineTarget(EngineId("koog"), EngineBindingId("b1"), ModelId("m1"))
        val first = app.profileSessions.open(ProfileId("p1")).graph as ModelSelectionsAccessor
        first.modelSelections.update { it.withDefault(target) }
        assertEquals(target, first.modelSelections.observe().first().defaultTarget)
        val other = app.profileSessions.open(ProfileId("p2")).graph as ModelSelectionsAccessor
        assertEquals(null, other.modelSelections.observe().first().defaultTarget)
        val again = app.profileSessions.open(ProfileId("p1")).graph as ModelSelectionsAccessor
        assertTrue(again.modelSelections.observe().first().isEnabled(target))
    }

    @Test
    fun `connection settings toggle is registered and off by default`() = runTest {
        val control = (app as TestToggleAccessors).toggleControl
        val state = control.observeStates().first().single { it.toggle == EngineConnectionsEnabled }
        assertEquals(false, state.value)
    }
}
