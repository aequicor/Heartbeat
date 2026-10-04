package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEnabled
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineSpec
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.OrganismTools
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.C2
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.ZYGOTE
import io.aequicor.heartbeat.feature.organicai.impl.cell
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiMachine
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganismAgentToolsTest {
    private val toggles = Toggles()

    private fun tools(vararg organisms: Organism, machine: SpecMachine = SpecMachine(*organisms)) =
        OrganismAgentTools(lazyOf(machine), toggles) to machine

    private fun context(session: SessionRef = session("z")) = AgentToolContext(session, null, TurnId("turn"))

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (key, value) ->
            key to when (value) {
                is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
                else -> JsonPrimitive(value as String)
            }
        },
    )

    @Test
    fun `cells see the tools only while an organism develops`() = runTest {
        val (developing, _) = tools(organism())
        assertEquals(
            listOf(OrganismTools.DIVIDE, OrganismTools.COMPLAIN, OrganismTools.DISPUTE, OrganismTools.STATUS),
            developing.specifications(null).map { it.name },
        )
        assertTrue(developing.specifications(null).all { it.action == AgentToolAction.Read })
        assertTrue(developing.isDetachedSupported)
        val (finished, _) = tools(organism().copy(status = OrganismStatus.Aborted))
        assertEquals(emptyList(), finished.specifications(null))
        toggles.isEnabled = false
        assertEquals(emptyList(), developing.specifications(null))
    }

    @Test
    fun `only a living cell's session can call the tools`() = runTest {
        val (tools, machine) = tools(organism())
        val stranger = tools.execute(context(session("chat")), OrganismTools.STATUS, args())
        assertTrue(stranger.isError)
        toggles.isEnabled = false
        assertTrue(tools.execute(context(), OrganismTools.STATUS, args()).isError)
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `a working cell divides into the next cell`() = runTest {
        val (tools, machine) = tools(organism())
        val result = tools.execute(
            context(),
            OrganismTools.DIVIDE,
            args(OrganismTools.Arguments.TASK to "write tests", OrganismTools.Arguments.NAME to "tests\nignored"),
        )
        assertFalse(result.isError, result.text)
        assertTrue("c1 \"tests\"" in result.text)
        val child = machine.organism().cells.last()
        assertEquals(listOf(C1, "tests", "write tests"), listOf(child.id, child.name, child.task))
        assertTrue(machine.effects.any { it is OrganicAiEffect.Drive && it.cell == C1 })
    }

    @Test
    fun `division beyond the limits or without a task is refused`() = runTest {
        val (limited, machine) = tools(organism().copy(limits = GrowthLimits(maxCells = 1)))
        val refused = limited.execute(context(), OrganismTools.DIVIDE, args(OrganismTools.Arguments.TASK to "x"))
        assertTrue(refused.isError && "limit of cells" in refused.text)
        assertTrue(limited.execute(context(), OrganismTools.DIVIDE, args()).isError)
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `complaints go to the immune system but never against the zygote`() = runTest {
        val (tools, machine) = tools(organism(cell(C1), cell(C2)))
        val zygote = tools.execute(
            context(session("c1")),
            OrganismTools.COMPLAIN,
            args(OrganismTools.Arguments.CELL to "zygote", OrganismTools.Arguments.REASON to "too slow"),
        )
        assertTrue(zygote.isError && "zygote cannot be accused" in zygote.text)
        val filed = tools.execute(
            context(session("c1")),
            OrganismTools.COMPLAIN,
            args(OrganismTools.Arguments.CELL to "c2", OrganismTools.Arguments.REASON to "deletes files"),
        )
        assertFalse(filed.isError, filed.text)
        assertEquals(
            listOf(ImmuneCase.Complaint(CaseId("k1"), C1, C2, "deletes files")),
            machine.organism().cases,
        )
        assertTrue(machine.effects.any { it is OrganicAiEffect.Judge })
        val unknown = tools.execute(
            context(session("c1")),
            OrganismTools.COMPLAIN,
            args(OrganismTools.Arguments.CELL to "c9", OrganismTools.Arguments.REASON to "x"),
        )
        assertTrue(unknown.isError)
    }

    @Test
    fun `disputes name known parties`() = runTest {
        val (tools, machine) = tools(organism(cell(C1)))
        val unknown = tools.execute(
            context(),
            OrganismTools.DISPUTE,
            args(OrganismTools.Arguments.QUESTION to "REST?", OrganismTools.Arguments.PARTIES to listOf("c7")),
        )
        assertTrue(unknown.isError)
        val filed = tools.execute(
            context(),
            OrganismTools.DISPUTE,
            args(OrganismTools.Arguments.QUESTION to "REST?", OrganismTools.Arguments.PARTIES to listOf("c1")),
        )
        assertFalse(filed.isError, filed.text)
        assertEquals(listOf(ImmuneCase.Dispute(CaseId("k1"), ZYGOTE, "REST?", listOf(C1))), machine.organism().cases)
    }

    @Test
    fun `status shows the cells and a race asks for a retry`() = runTest {
        val (tools, _) = tools(organism(cell(C1, phase = CellPhase.Resting)))
        val status = tools.execute(context(), OrganismTools.STATUS, args())
        assertTrue("You are the zygote." in status.text && "- c1 \"cell c1\"" in status.text, status.text)

        val (racing, _) = tools(organism(), machine = SpecMachine(organism(), result = SendResult.Ignored))
        val raced = racing.execute(context(), OrganismTools.DIVIDE, args(OrganismTools.Arguments.TASK to "x"))
        assertEquals(AgentToolResult("The organism changed meanwhile; retry", isError = true), raced)
    }

    /** Runs the real spec without a runtime, recording intents and effects. */
    class SpecMachine(vararg organisms: Organism, private val result: SendResult? = null) : OrganicAiMachine {
        override val name: String = OrganicAiMachineSpec.name
        override val state = MutableStateFlow<OrganicAiState>(OrganicAiState.Living(organisms.associateBy { it.id }))
        override val outputs: Flow<OrganicAiOutput> = MutableSharedFlow()
        val sent = mutableListOf<OrganicAiIntent>()
        val effects = mutableListOf<OrganicAiEffect>()

        fun organism(): Organism = (state.value as OrganicAiState.Living).organisms.getValue(ORGANISM)

        override suspend fun send(intent: OrganicAiIntent): SendResult {
            sent += intent
            val resolution = OrganicAiMachineSpec.resolve(state.value, intent).takeIf { result == null }
            resolution?.let {
                state.value = it.to
                effects += it.effects
            }
            return result ?: if (resolution == null) SendResult.Ignored else SendResult.Accepted
        }
    }

    class Toggles(var isEnabled: Boolean = true) : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // The fake answers only the organic AI flag.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(isEnabled as T)

        @Suppress("UNCHECKED_CAST") // The fake answers only the organic AI flag.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T {
            check(toggle == OrganicAiEnabled)
            return isEnabled as T
        }
    }
}
