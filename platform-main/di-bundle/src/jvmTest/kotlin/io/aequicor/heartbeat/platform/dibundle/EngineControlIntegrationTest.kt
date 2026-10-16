package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlPanelRoute
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateEngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Profile accessor of the engine configuration the control panel edits. */
@ContributesTo(ProfileScope::class)
interface EngineConfigurationAccessor {
    val engineConfiguration: EngineConfiguration
}

/** The engine control panel through the real graph: its routes, its toggle and the configuration Pi declares. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineControlIntegrationTest {
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
    fun `the engine list and an engine panel open in the profile tree`() = runTest {
        val session = app.profileSessions.open(ProfileId("p1"))
        val context = DefaultComponentContext(LifecycleRegistry().apply { resume() })
        val profile = (session.graph as ProfileNavigation).navigation
            .create(context, initial = listOf(EngineControlRoute(isEmbedded = true)), name = "profile")
        profile.navigator.navigate(EngineControlPanelRoute(EngineId("pi")))

        assertEquals(
            listOf(EngineControlRoute(isEmbedded = true), EngineControlPanelRoute(EngineId("pi"))),
            profile.stack.value.items.map { it.configuration.route },
        )
    }

    @Test
    fun `engine configuration is registered off and leaves the configuration empty`() = runTest {
        val control = (app as TestToggleAccessors).toggleControl
        val state = control.observeStates().first().single { it.toggle == EngineConfigurationEnabled }
        assertEquals(false, state.value)

        val configuration = (app.profileSessions.open(ProfileId("p1")).graph as EngineConfigurationAccessor)
            .engineConfiguration
        assertFalse(configuration.state.value.isEnabled)
        assertTrue(configuration.state.value.engines.isEmpty())
    }

    @Test
    fun `Pi declares a valid configuration once engine configuration is on`() = runTest {
        val toggles = (app as TestToggleAccessors).toggleControl
        listOf(AiEngines, PiEnabled, EngineManagementEnabled, EngineConfigurationEnabled)
            .forEach { toggles.setOverride(it, true) }
        val configuration = (app.profileSessions.open(ProfileId("p1")).graph as EngineConfigurationAccessor)
            .engineConfiguration

        // The configuration follows storage and toggles on real dispatchers.
        val state = withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT_MILLIS) {
                configuration.state.first { it.isEnabled && it.engine(EngineId("pi")) != null }
            }
        }
        val pi = checkNotNull(state.engine(EngineId("pi")))
        assertEquals(emptyList(), validateEngineConfigSchema(pi.schema))
        assertTrue(
            pi.schema.capabilities.containsAll(
                setOf(ConfigCapability.ImportNative, ConfigCapability.ProjectTrust, ConfigCapability.Secrets),
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
