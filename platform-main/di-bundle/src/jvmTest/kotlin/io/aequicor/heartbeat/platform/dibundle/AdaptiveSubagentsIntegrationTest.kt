package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsEnabled
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsResearch
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsRouter
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsSessionTools
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesMachineKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AdaptiveSubagentsIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `adaptive subagent toggles are registered and off by default`() {
        val flags = listOf(
            AdaptiveSubagentsEnabled,
            AdaptiveSubagentsResearch,
            AdaptiveSubagentsRouter,
            AdaptiveSubagentsSessionTools,
        )
        for (flag in flags) {
            assertTrue(flag in toggles.toggleControl.registered, flag.key)
            assertFalse(flag.default, flag.key)
            assertEquals("adaptive_subagents", flag.owner)
        }
    }

    @Test
    fun `a saved model description survives reopening the profile`() = runTest {
        val key = ModelKey(EngineId("codex"), ModelId("gpt-5"))
        val session = app.profileSessions.open(ProfileId("adaptive"))
        val first = session.graph as TestAdaptiveAccessors
        ready(first)
        val profile = ModelProfile(key, "GPT-5", "Strong coder", updatedAt = Instant.fromEpochSeconds(1))
        first.machines.send(ModelProfilesMachineKey, ModelProfilesIntent.Public.Save(profile, null))
        withContext(app.dispatchers.default) {
            first.modelProfiles.state.first { it.hasProfile(key) }
            // The save effect runs after the transition: wait until the document reaches the profile store.
            val stored = (session.graph as TestStorageAccessors).stores.keyValue(KeyValueSpec("adaptive_subagents"))
            stored.observe(jsonKey("library", JsonObject.serializer())).first {
                it?.get("profiles")?.jsonArray?.isNotEmpty() == true
            }
        }
        app.profileSessions.close()

        val reopened = app.profileSessions.open(ProfileId("adaptive")).graph as TestAdaptiveAccessors
        val loaded = ready(reopened)
        assertEquals("Strong coder", loaded.profileOf(key)?.summary)
        app.profileSessions.close()
    }

    private suspend fun ready(graph: TestAdaptiveAccessors): ModelProfilesState.Ready = withContext(
        app.dispatchers.default,
    ) {
        graph.modelProfiles.state.first { it is ModelProfilesState.Ready } as ModelProfilesState.Ready
    }

    private fun ModelProfilesState.hasProfile(key: ModelKey) = (this as? ModelProfilesState.Ready)?.profileOf(
        key,
    ) != null
}

@ContributesTo(ProfileScope::class)
interface TestAdaptiveAccessors {
    val modelProfiles: ModelProfilesView
    val machines: MachineRegistry
}
