package io.aequicor.heartbeat.feature.agentlearning.impl.data

import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningLimits
import io.aequicor.heartbeat.feature.agentlearning.api.ModelScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigContentKind
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningConfigSectionTest {
    private val skill = LearnedInstruction(
        InstructionId("1"),
        InstructionKind.Skill,
        PROJECT,
        "Кодировка консоли",
        "Use UTF-8 in every console.",
        description = "When a console prints garbage",
        createdAtMillis = 1,
    )
    private val model = LearnedInstruction(
        InstructionId("2"),
        InstructionKind.Model,
        null,
        "Short answers",
        "Answer briefly.",
        modelScope = ModelScope(EngineId("codex"), ModelId("gpt")),
        isEnabled = false,
        createdAtMillis = 2,
    )
    private val machine = SpecMachine(AgentLearningState.Ready(listOf(skill, model)))
    private val toggles = Toggles()
    private val section = LearningConfigSection(machine, toggles)

    @Test
    fun `entities encode to files that check back to the same identity and canonical form`() = runTest {
        val entities = section.entities.first().orEmpty()
        assertEquals(listOf("kodirovka-konsoli", "short-answers"), entities.map { it.name })

        for (entity in entities) {
            val unit = section.encode(entity.key, entity.name)!!
            val check = assertIs<ConfigCheck.Valid>(section.check(unit))
            assertEquals(entity.identity, check.identity)
            assertEquals(unit, check.canonical)
        }
        val unit = section.encode(ConfigEntityKey("1"), "custom")!!
        assertEquals(listOf("learning/custom.json", "learning/custom.md"), unit.files.map { it.path.value })
        val summary = (section.check(unit) as ConfigCheck.Valid).summary
        assertEquals(ConfigContentKind.LearnedSkill, summary.contents.single().kind)
        assertFalse(summary.hasCode)
    }

    @Test
    fun `equal titles get distinct names and only files directly in the section are units`() {
        assertEquals("a", section.unitOf(ConfigPath("learning/a.json")))
        assertEquals("a", section.unitOf(ConfigPath("learning/a.md")))
        assertNull(section.unitOf(ConfigPath("learning/a.txt")))
        assertNull(section.unitOf(ConfigPath("learning/sub/a.md")))
        assertNull(section.unitOf(ConfigPath("harnesses/a.md")))
    }

    @Test
    fun `a new unit is learned for the target project and a bound one replaced keeping its switch`() = runTest {
        val unit = unit("deploy", """{"kind":"general","title":"Deploy"}""", "Deploy with make.\r\n")
        val project = WorkspaceRef("other")

        val created = assertIs<ConfigApplyResult.Applied>(section.apply(unit, null, ConfigApplyContext(project)))
        val learned = ready().instructions.single { it.id.value == created.key.value }
        assertEquals(project, learned.project)
        assertEquals("Deploy with make.", learned.content)
        assertTrue(learned.isEnabled)

        val replacement = unit("x", """{"kind":"general","title":"Short answers v2"}""", "Answer very briefly.")
        val replaced = section.apply(replacement, ConfigEntityKey("2"), ConfigApplyContext(project))
        assertEquals(ConfigApplyResult.Applied(ConfigEntityKey("2")), replaced)
        val updated = ready().instructions.single { it.id == model.id }
        assertEquals(InstructionKind.General, updated.kind)
        assertEquals("Short answers v2", updated.title)
        assertFalse(updated.isEnabled)
        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.NotFound),
            section.apply(replacement, ConfigEntityKey("404"), ConfigApplyContext(project)),
        )
    }

    @Test
    fun `invalid units name their problems without texts`() = runTest {
        val missingBody = ConfigUnit.of(
            ConfigUnitRef(ConfigSectionId.Learning, "a"),
            listOf(ConfigFile(ConfigPath("learning/a.json"), """{"kind":"general","title":"A"}""")),
        )
        assertEquals(ConfigProblemCode.Missing, problem(missingBody))
        assertEquals(ConfigProblemCode.Syntax, problem(unit("a", "{", "x")))
        assertEquals(ConfigProblemCode.Syntax, problem(unit("a", """{"kind":"skill","title":"A"}""", "x")))
        assertEquals(ConfigProblemCode.Syntax, problem(unit("a", """{"kind":"model","title":"A"}""", "x")))
        assertEquals(
            ConfigProblemCode.Syntax,
            problem(unit("a", """{"kind":"general","title":"A","engine":"c"}""", "x")),
        )
        assertEquals(ConfigProblemCode.Syntax, problem(unit("a", """{"kind":"general","title":"A","secret":1}""", "x")))
        assertEquals(
            ConfigProblemCode.Limit,
            problem(unit("a", """{"kind":"general","title":"A"}""", "x".repeat(LearningLimits.CONTENT + 1))),
        )
        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.Invalid),
            section.apply(unit("a", "{", "x"), null, ConfigApplyContext(null)),
        )
    }

    @Test
    fun `removal deletes the instruction and self-learning off makes the section unavailable`() = runTest {
        assertEquals(ConfigApplyResult.Applied(ConfigEntityKey("1")), section.remove(ConfigEntityKey("1")))
        assertTrue(ready().instructions.none { it.id == skill.id })
        assertEquals(ConfigApplyResult.Rejected(ConfigRejection.NotFound), section.remove(ConfigEntityKey("1")))

        toggles.isEnabled = false
        assertNull(LearningConfigSection(machine, toggles).entities.first())
        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.Unavailable),
            section.apply(unit("a", """{"kind":"general","title":"A"}""", "x"), null, ConfigApplyContext(null)),
        )
    }

    private suspend fun problem(unit: ConfigUnit) =
        assertIs<ConfigCheck.Invalid>(section.check(unit)).problems.first().code

    private fun ready() = machine.state.value as AgentLearningState.Ready

    private fun unit(name: String, metadata: String, body: String) = ConfigUnit.of(
        ConfigUnitRef(ConfigSectionId.Learning, name),
        listOf(
            ConfigFile(ConfigPath("learning/$name.json"), metadata),
            ConfigFile(ConfigPath("learning/$name.md"), body),
        ),
    )
}
