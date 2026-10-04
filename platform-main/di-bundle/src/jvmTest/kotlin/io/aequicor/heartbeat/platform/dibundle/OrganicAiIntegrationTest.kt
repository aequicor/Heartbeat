package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEnabled
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismTools
import io.aequicor.heartbeat.feature.organicai.api.Work
import io.aequicor.heartbeat.feature.organicai.api.zygote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganicAiIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors
    private val organism = OrganismId("o1")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `organic AI is a registered toggle and its machine stays closed while off`() = runTest {
        assertTrue(OrganicAiEnabled in toggles.toggleControl.registered)
        assertFalse(OrganicAiEnabled.default)
        assertEquals("organic_ai", OrganicAiEnabled.owner)
        val profile = app.profileSessions.open(ProfileId("organic-off"))
        assertNull(app.machines.find(OrganicAiMachineKey))
        assertEquals(SendResult.NotRunning, app.machines.send(OrganicAiMachineKey, OrganicAiIntent.Public.Awaken))
        val tools = bounded("tools") { (profile.graph as TestOrganicAccessors).organicTools.specifications(null) }
        assertTrue(tools.none { it.name == OrganismTools.DIVIDE })
        app.profileSessions.close()
    }

    @Test
    fun `an organism without a model stalls, survives reopening and sleeps with the toggle`() = runTest {
        toggles.toggleControl.setOverride(OrganicAiEnabled, true)
        val profile = app.profileSessions.open(ProfileId("organic"))
        val machine = awake()
        val conceived = machine.send(OrganicAiIntent.Public.Conceive(Conception(organism, "Build the thing")))
        assertEquals(SendResult.Accepted, conceived)
        bounded("stalled zygote") { machine.state.first { it.zygotePhase() is CellPhase.Stalled } }
        val journal = (profile.graph as TestStorageAccessors).stores.keyValue(KeyValueSpec("organic_ai_organisms"))
        bounded(
            "journaled stall",
        ) { journal.observe(stringKey("organism.o1")).first { it?.contains("Stalled") == true } }
        val tools = bounded("tools") { (profile.graph as TestOrganicAccessors).organicTools.specifications(null) }
        assertTrue(tools.any { it.name == OrganismTools.DIVIDE })
        app.profileSessions.close()

        app.profileSessions.open(ProfileId("organic"))
        val restored = awake()
        assertEquals(CellPhase.Stalled(Breakdown.NoModel, Work.Genesis), restored.state.value.zygotePhase())

        toggles.toggleControl.setOverride(OrganicAiEnabled, false)
        bounded("sleep") { restored.state.first { it == OrganicAiState.Dormant } }
        app.profileSessions.close()
    }

    private suspend fun awake(): MachineRef<OrganicAiState, OrganicAiIntent.Public, OrganicAiOutput> {
        val machine = bounded("organic AI machine") { app.machines.observe(OrganicAiMachineKey).first { it != null } }
        bounded("living organisms") { machine.state.first { it is OrganicAiState.Living } }
        return machine
    }

    private fun OrganicAiState.zygotePhase(): CellPhase? =
        (this as? OrganicAiState.Living)?.organisms?.get(organism)?.zygote?.phase

    private suspend fun <T : Any> bounded(checkpoint: String, action: suspend () -> T?): T =
        withContext(app.dispatchers.default) {
            try {
                withTimeout(TIMEOUT_MILLIS) { action() } ?: throw AssertionError("Timed out at $checkpoint")
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("Timed out at $checkpoint", e)
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}

/** Hosted tools of the profile, as engines see them. */
@ContributesTo(ProfileScope::class)
interface TestOrganicAccessors {
    val organicTools: ProfileAgentTools
}
