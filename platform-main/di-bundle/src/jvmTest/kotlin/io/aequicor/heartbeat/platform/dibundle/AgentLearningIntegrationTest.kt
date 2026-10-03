package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentLearningIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `learning is a registered toggle disabled by default`() = runTest {
        assertTrue(AgentLearningEnabled in toggles.toggleControl.registered)
        assertFalse(AgentLearningEnabled.default)
        assertEquals("agent_learning", AgentLearningEnabled.owner)
        val profile = app.profileSessions.open(ProfileId("learning-off"))
        assertTrue((profile.graph as TestLearningAccessors).agentTools.specifications(null).isEmpty())
        app.profileSessions.close()
    }

    @Test
    fun `a lesson remembered in a chat without a project reaches later chats after reopening`() = runTest {
        toggles.toggleControl.setOverride(AgentLearningEnabled, true)
        val first = app.profileSessions.open(ProfileId("learning"))
        val tools = (first.graph as TestLearningAccessors).agentTools
        assertEquals(
            listOf(LearningTools.REMEMBER, LearningTools.LOAD_SKILL),
            tools.specifications(null).map { it.name },
        )
        val arguments = JsonObject(
            mapOf(
                "kind" to "general",
                "title" to "Console encoding",
                "content" to "Switch the console to UTF-8 before running scripts",
                "safety" to "safe",
            ).mapValues { JsonPrimitive(it.value) },
        )
        val saved = withContext(app.dispatchers.default) {
            tools.execute(context(), LearningTools.REMEMBER, arguments)
        }
        assertFalse(saved.isError, saved.text)
        app.profileSessions.close()

        val reopened = app.profileSessions.open(ProfileId("learning"))
        val instructions = withContext(app.dispatchers.default) {
            (reopened.graph as TestLearningAccessors).agentTools.instructions(AgentToolScope(null))
        }
        assertTrue("Console encoding" in instructions, instructions)
        app.profileSessions.close()
    }

    // New instructions are confirmed by default; the user approves this one.
    private fun context() = AgentToolContext(
        SessionRef(EngineId("koog"), SessionSourceId("local"), "native"),
        null,
        TurnId("turn"),
        trust = TrustLevel.Full,
        permissions = AgentToolPermissions { true },
    )
}

@ContributesTo(ProfileScope::class)
interface TestLearningAccessors {
    val agentTools: ProfileAgentTools
}
