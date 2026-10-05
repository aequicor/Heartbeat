package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.checklist.api.ChecklistAnswer
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistMachineKey
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.RunStartedEvent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ChecklistIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors
    private val session = SessionRef(EngineId("koog"), SessionSourceId("local"), "native")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `checklists default off and ready mode persists with automatic wakes disabled`() = runTest {
        assertTrue(ChecklistEnabled in toggles.toggleControl.registered)
        assertFalse(ChecklistEnabled.default)
        toggles.toggleControl.setOverride(ChecklistEnabled, true)
        val first = app.profileSessions.open(ProfileId("checklist"))
        val access = first.graph as TestChecklistAccessors
        withContext(app.dispatchers.default) {
            withTimeout(10.seconds) {
                val machine = access.machines.observe(ChecklistMachineKey).filterNotNull().first()
                machine.state.filterIsInstance<ChecklistState.Ready>().first()
                val request = RequestId("request")
                access.bus.publish(
                    SchedulerEvents.RunStarted,
                    EventOrigin.Host,
                    Json.encodeToString(RunStartedEvent(session, request, 1)),
                )
                machine.state.filterIsInstance<ChecklistState.Ready>().first { it.journal.generations.isNotEmpty() }
                val result = access.tools.execute(
                    AgentToolContext(session, null, TurnId("native-turn"), request, trust = TrustLevel.Full),
                    "checklist_create",
                    Json.parseToJsonElement(
                        """{"key":"verify","title":"Check","mode":"MarkSessionReady",
                        "fields":[{"id":"text","title":"Notes","type":"Text"}]}""",
                    ).jsonObject,
                )
                assertFalse(result.isError, result.text)
                val card = (machine.state.value as ChecklistState.Ready).journal.cards.single()
                machine.send(ChecklistIntent.Public.Answer(card.id, "text", ChecklistAnswer(text = "tested")))
                machine.state.filterIsInstance<ChecklistState.Ready>().first { it.savedRevision == it.journal.revision }
            }
        }
        app.profileSessions.close()
        val reopened = app.profileSessions.open(ProfileId("checklist"))
        withContext(app.dispatchers.default) {
            withTimeout(10.seconds) {
                val machine = (reopened.graph as TestChecklistAccessors).machines.observe(ChecklistMachineKey)
                    .filterNotNull().first()
                val ready = machine.state.filterIsInstance<ChecklistState.Ready>().first()
                assertEquals("tested", ready.journal.cards.single().answers["text"]?.text)
            }
        }
        app.profileSessions.close()
    }
}

@ContributesTo(ProfileScope::class)
interface TestChecklistAccessors {
    val tools: ProfileAgentTools
    val machines: MachineRegistry
    val bus: SchedulerBus
}
