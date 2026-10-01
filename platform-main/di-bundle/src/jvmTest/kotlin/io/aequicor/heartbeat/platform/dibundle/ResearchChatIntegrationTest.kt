package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatMachineKey
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatOutput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ResearchChatIntegrationTest {
    private val target = EngineTarget(EngineId("koog"), EngineBindingId("research-test"), ModelId("model"))

    @Test
    fun `flag is registered off and restoring a profile retains questions with correct source scope`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val persisted = PersistedProfile()
        val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
        val lifecycle = LifecycleRegistry().apply { resume() }
        try {
            enableResearch(app)
            val profile = app.profileSessions.open(ProfileId("research"))
            (profile.graph as ProfileNavigation).navigation.create(
                DefaultComponentContext(lifecycle),
                listOf(ResearchChatRoute(target)),
            )
            val machine = checkNotNull(app.machines.find(ResearchChatMachineKey))
            val initial = machine.state.first { it is ResearchChatState.Ready } as ResearchChatState.Ready
            val first = requireNotNull(initial.questionId)
            app.machines.send(
                ResearchChatMachineKey,
                source("Shared notes", "Source text"),
            )
            sources(machine, 1)
            app.machines.send(ResearchChatMachineKey, ResearchChatIntent.Public.NewQuestion)
            machine.state.first { it is ResearchChatState.Ready && it.questionId != first && !it.isMutating }
            app.machines.send(
                ResearchChatMachineKey,
                source("Local notes", "Private source"),
            )
            val ready = sources(machine, 2)
            assertTrue(requireNotNull(ready.session).resources.all { it.attachmentId != null })
            assertEquals(1, ready.session?.sharedResourceIds?.size)
            assertEquals(2, ready.session?.selectedResources(requireNotNull(ready.question))?.size)
            lifecycle.destroy()
            app.profileSessions.close()

            val restored = app.profileSessions.open(ProfileId("research"))
            val reopened = LifecycleRegistry().apply { resume() }
            try {
                (restored.graph as ProfileNavigation).navigation.create(
                    DefaultComponentContext(reopened),
                    listOf(ResearchChatRoute(target)),
                )
                val state = checkNotNull(app.machines.find(ResearchChatMachineKey)).state.first {
                    it is ResearchChatState.Ready
                } as ResearchChatState.Ready
                assertEquals(ready.session, state.session)
                assertEquals(1, state.session?.selectedResources(requireNotNull(state.question))?.size)
            } finally {
                reopened.destroy()
            }
        } finally {
            (app.appScope as OwnedScope).close()
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }
    private suspend fun sources(
        machine: MachineRef<ResearchChatState, ResearchChatIntent.Public, ResearchChatOutput>,
        count: Int,
    ): ResearchChatState.Ready {
        val state = machine.state.first {
            it is ResearchChatState.Ready && (it.hasError || (!it.isMutating && it.session?.resources?.size == count))
        } as ResearchChatState.Ready
        assertFalse(state.hasError, "Research source import failed")
        return state
    }

    private suspend fun enableResearch(app: TestAppGraph) {
        val flags = (app as TestToggleAccessors).toggleControl
        assertTrue(flags.registered.contains(ResearchChatEnabled))
        assertEquals(false, ResearchChatEnabled.default)
        flags.setOverride(ResearchChatEnabled, true)
        flags.setOverride(KoogEngineEnabled, true)
        flags.setOverride(AttachmentsEnabled, true)
    }

    private fun source(title: String, text: String) = ResearchChatIntent.Public.AddResource(
        ResearchResourceKind.Document,
        title,
        text,
        ResearchResourceScope.Question,
    )
}
