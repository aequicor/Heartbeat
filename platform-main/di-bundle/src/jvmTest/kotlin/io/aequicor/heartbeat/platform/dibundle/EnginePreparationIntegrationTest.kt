package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.ConnectWizardGraph
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.ConnectWizardScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardModel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.PreparationPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.WizardStep
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparationFailure
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.di.AiStudioGraph
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnginePreparationPhaseUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pro.respawn.flowmvi.dsl.collect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Resolves the production wizard model through its actual Metro child graph. */
@ContributesTo(ConnectWizardScope::class)
interface PreparationWizardTestAccessors {
    val model: ConnectWizardModel
}

@OptIn(ExperimentalCoroutinesApi::class)
class EnginePreparationIntegrationTest {
    @Test
    fun `actual feature graphs deliver runtime download failure and recovery without engine management`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        TestAdapter.preparation.value = EnginePreparation.Idle
        try {
            val flags = (app as TestToggleAccessors).toggleControl
            flags.setOverride(AiEngines, true)
            flags.setOverride(TestAdapter.toggle, true)
            flags.setOverride(StudioEngineRuntime, true)
            flags.setOverride(EngineManagementEnabled, false)
            val profile = app.profileSessions.open(ProfileId("runtime-preparation"))
            val studio = (profile.graph as AiStudioGraph.Factory).createAiStudio(
                app.scopes.child(profile.graph.scope, "preparation-studio"),
                StudioScreenHost.Window,
            ).model
            val wizard = (profile.graph as ConnectWizardGraph.Factory).createConnectWizard(
                ConnectEngineRoute(TestAdapter.engine),
                app.scopes.child(profile.graph.scope, "preparation-wizard"),
            ) as PreparationWizardTestAccessors
            val studioStates = MutableStateFlow<AiStudioScreenState?>(null)
            val wizardStates = MutableStateFlow<ConnectWizardScreenState?>(null)
            backgroundScope.launch {
                studio.store.collect { states.collect { studioStates.value = it } }
            }
            backgroundScope.launch {
                wizard.model.store.collect { states.collect { wizardStates.value = it } }
            }
            wizardStates.filterNotNull().first { it.step == WizardStep.Method }
            assertTrue(studioStates.filterNotNull().first().enginePreparations.isEmpty())

            TestAdapter.preparation.value = EnginePreparation.Downloading(42, 100)
            val downloadingStudio = studioStates.filterNotNull().first {
                it.enginePreparations.singleOrNull()?.phase == EnginePreparationPhaseUi.Downloading
            }
            val downloadingWizard = wizardStates.filterNotNull().first {
                it.preparation?.phase == PreparationPhaseUi.Downloading
            }
            assertEquals(42, downloadingStudio.enginePreparations.single().percent)
            assertEquals("Integration engine", downloadingStudio.enginePreparations.single().engine)
            assertEquals(42, downloadingWizard.preparation?.percent)

            TestAdapter.preparation.value = EnginePreparation.Failed(EnginePreparationFailure.Network)
            studioStates.filterNotNull().first {
                it.enginePreparations.singleOrNull()?.phase == EnginePreparationPhaseUi.Network
            }
            wizardStates.filterNotNull().first { it.preparation?.phase == PreparationPhaseUi.Network }

            TestAdapter.preparation.value = EnginePreparation.Ready
            val readyStudio = studioStates.filterNotNull().first { it.enginePreparations.isEmpty() }
            assertTrue(readyStudio.enginePreparations.isEmpty())
            assertNull(wizardStates.filterNotNull().first { it.preparation == null }.preparation)
        } finally {
            app.closeAndAwaitStorages()
            TestAdapter.preparation.value = EnginePreparation.Ready
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
}
