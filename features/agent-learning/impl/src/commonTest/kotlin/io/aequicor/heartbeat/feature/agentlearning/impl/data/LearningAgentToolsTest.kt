package io.aequicor.heartbeat.feature.agentlearning.impl.data

import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools
import io.aequicor.heartbeat.feature.agentlearning.api.ModelScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearningAgentToolsTest {
    @Test
    fun `tools exist in every session only while learning is on`() = runTest {
        val toggles = Toggles(isEnabled = false)
        val tools = tools(toggles = toggles)
        assertTrue(tools.isDetachedSupported)
        assertEquals(emptyList(), tools.specifications(null))
        assertEquals("", tools.instructions(AgentToolScope(PROJECT, TARGET)))
        toggles.isEnabled = true
        val names = listOf(LearningTools.REMEMBER, LearningTools.LOAD_SKILL)
        assertEquals(names, tools.specifications(null).map { it.name })
        assertEquals(tools.specifications(null), tools.specifications(PROJECT))
    }

    @Test
    fun `remember binds the lesson to the project of the session`() = runTest {
        val machine = SpecMachine()
        val tools = tools(machine)
        val saved = tools.execute(context(), LearningTools.REMEMBER, remember("general", "UTF-8 console"))
        assertFalse(saved.isError, saved.text)
        val detached = tools.execute(context(workspace = null), LearningTools.REMEMBER, remember("general", "Plain"))
        assertFalse(detached.isError, detached.text)
        val stored = (machine.state.value as AgentLearningState.Ready).instructions
        assertEquals(listOf(PROJECT, null), stored.map { it.project })
        assertEquals(listOf("UTF-8 console", "Plain"), stored.map { it.title })

        val duplicate = tools.execute(context(), LearningTools.REMEMBER, remember("general", "utf-8 CONSOLE"))
        assertTrue(duplicate.isError)
        val unknown = tools.execute(context(WorkspaceRef("other")), LearningTools.REMEMBER, remember("general", "X"))
        assertTrue(unknown.isError)
    }

    @Test
    fun `a worktree checkout learns for its original project`() = runTest {
        val machine = SpecMachine()
        val task = WorktreeTask("chat", PROJECT, executionWorkspace = CHECKOUT)
        val tools = tools(machine, worktrees = Worktrees(mapOf("chat" to task)))
        assertFalse(tools.execute(context(CHECKOUT), LearningTools.REMEMBER, remember("general", "Lesson")).isError)
        assertEquals(PROJECT, (machine.state.value as AgentLearningState.Ready).instructions.single().project)
    }

    @Test
    fun `model lessons take the engine and model from the trusted context`() = runTest {
        val machine = SpecMachine()
        val tools = tools(machine)
        tools.execute(context(), LearningTools.REMEMBER, remember("model", "Quirk", "model_scope" to "model"))
        tools.execute(context(), LearningTools.REMEMBER, remember("model", "Engine quirk", "model_scope" to "engine"))
        val noModel = tools.execute(context(target = null), LearningTools.REMEMBER, remember("model", "Lost"))
        assertTrue(noModel.isError)
        assertEquals(
            listOf(ModelScope(TARGET.engine, TARGET.model), ModelScope(TARGET.engine)),
            (machine.state.value as AgentLearningState.Ready).instructions.map { it.modelScope },
        )
    }

    @Test
    fun `invalid and secret looking lessons are refused`() = runTest {
        val machine = SpecMachine()
        val tools = tools(machine)
        val missing = tools.execute(context(), LearningTools.REMEMBER, JsonObject(mapOf("kind" to JsonPrimitive("x"))))
        val long = remember("general", "Long", "content" to "x".repeat(2_001))
        val secret = remember("general", "Key", "content" to "Use api_key=abcdef1234567890 for requests")
        val skill = remember("skill", "Release")
        for (arguments in listOf(long, secret, skill)) {
            assertTrue(tools.execute(context(), LearningTools.REMEMBER, arguments).isError)
        }
        assertTrue(missing.isError)
        assertEquals(emptyList(), (machine.state.value as AgentLearningState.Ready).instructions)
    }

    @Test
    fun `hidden characters are refused and an unknown workspace gets no learning prompt`() = runTest {
        val tools = tools()
        val hidden = remember("general", "Bidi", "content" to "Run \u202Erm -rf\u202C safely")
        val tagged = remember("general", "Tags", "content" to "Use UTF-8\uDB40\uDC41")
        assertTrue(tools.execute(context(), LearningTools.REMEMBER, hidden).isError)
        assertTrue(tools.execute(context(), LearningTools.REMEMBER, tagged).isError)
        val multiline = remember("general", "Lines", "content" to "First line\n\tSecond line")
        assertFalse(tools.execute(context(), LearningTools.REMEMBER, multiline).isError)
        assertEquals("", tools.instructions(AgentToolScope(WorkspaceRef("unknown"), TARGET)))
    }

    @Test
    fun `approval level and the agent's rating decide whether the user is asked`() = runTest {
        val machine = SpecMachine()
        val tools = tools(machine)
        val spec = tools.specifications(PROJECT).first { it.name == LearningTools.REMEMBER }
        val safe = remember("general", "Safe")
        val review = remember("general", "Review", "safety" to "review")
        val cases = mapOf(
            LearningApproval.Ask to listOf(true, true),
            LearningApproval.Automatic to listOf(false, true),
            LearningApproval.AcceptAll to listOf(false, false),
        )
        for ((level, expected) in cases) {
            machine.state.value = AgentLearningState.Ready(approval = level)
            val actual = listOf(safe, review).map { tools.requiresDecision(context(), spec, it) }
            assertEquals(expected, actual, "$level")
            assertEquals("approval=$level", tools.approval(context(), spec, safe).binding)
        }
        val load = tools.specifications(PROJECT).first { it.name == LearningTools.LOAD_SKILL }
        assertFalse(tools.requiresDecision(context(), load, safe))
        // A call execution refuses anyway is not put to the user.
        machine.state.value = AgentLearningState.Ready(approval = LearningApproval.Ask)
        assertFalse(tools.requiresDecision(context(), spec, JsonObject(emptyMap())))
        assertFalse(tools.requiresDecision(context(WorkspaceRef("unknown")), spec, safe))
        assertTrue(checkNotNull(tools.approval(context(), spec, safe).description).contains("Content of Safe"))
    }

    @Test
    fun `session instructions contain only enabled lessons of its project and model`() = runTest {
        val other = ModelScope(EngineId("codex"))
        val machine = SpecMachine(
            AgentLearningState.Ready(
                listOf(
                    lesson("1", "Project rule"),
                    lesson("2", "Disabled rule").copy(isEnabled = false),
                    lesson("3", "Chat rule").copy(project = null),
                    lesson("4", "Sonnet quirk", InstructionKind.Model, ModelScope(TARGET.engine, TARGET.model)),
                    lesson("5", "Codex quirk", InstructionKind.Model, other),
                    lesson("6", "Opus quirk", InstructionKind.Model, ModelScope(TARGET.engine, ModelId("opus"))),
                    lesson("7", "Release", InstructionKind.Skill).copy(description = "When releasing"),
                ),
            ),
        )
        val text = tools(machine).instructions(AgentToolScope(PROJECT, TARGET))
        assertTrue("Windows" in text)
        for (included in listOf("Project rule", "Sonnet quirk", "Release — When releasing")) {
            assertTrue(included in text, included)
        }
        for (excluded in listOf("Disabled rule", "Chat rule", "Codex quirk", "Opus quirk", "Content of Release")) {
            assertFalse(excluded in text, excluded)
        }
        val chat = tools(machine).instructions(AgentToolScope(null, TARGET))
        assertTrue("Chat rule" in chat)
        assertFalse("Project rule" in chat)
    }

    @Test
    fun `learned skills load by name within the session's project`() = runTest {
        val skill = lesson("7", "Release", InstructionKind.Skill).copy(description = "When releasing")
        val tools = tools(SpecMachine(AgentLearningState.Ready(listOf(skill))))
        val loaded = tools.execute(
            context(),
            LearningTools.LOAD_SKILL,
            JsonObject(mapOf("name" to JsonPrimitive("release"))),
        )
        assertEquals("Content of Release", loaded.text)
        val missing = tools.execute(
            context(null),
            LearningTools.LOAD_SKILL,
            JsonObject(mapOf("name" to JsonPrimitive("Release"))),
        )
        assertTrue(missing.isError)
    }

    private fun remember(kind: String, title: String, vararg extra: Pair<String, String>): JsonObject = JsonObject(
        (
            mapOf("kind" to kind, "title" to title, "content" to "Content of $title", "safety" to "safe") +
                extra
        ).mapValues { JsonPrimitive(it.value) },
    )

    private fun lesson(
        id: String,
        title: String,
        kind: InstructionKind = InstructionKind.General,
        scope: ModelScope? = null,
    ) = LearnedInstruction(InstructionId(id), kind, PROJECT, title, "Content of $title", modelScope = scope)
}
