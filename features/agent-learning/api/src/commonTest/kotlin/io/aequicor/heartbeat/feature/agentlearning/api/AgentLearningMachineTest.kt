package io.aequicor.heartbeat.feature.agentlearning.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentLearningMachineTest {
    private val spec = AgentLearningMachineSpec
    private val project = WorkspaceRef("project")
    private val encoding = instruction("1", "Use UTF-8 console")
    private val ready = AgentLearningState.Ready(listOf(encoding), revision = 3)

    @Test
    fun `start loads the registry and a failed load blocks writes until reload`() {
        spec.assertTransition(
            AgentLearningState.Idle,
            AgentLearningIntent.Public.Start,
            AgentLearningState.Loading,
            effects = listOf(AgentLearningEffect.Load),
        )
        spec.assertTransition(
            AgentLearningState.Loading,
            AgentLearningIntent.Internal.Loaded(listOf(encoding, encoding), LearningApproval.Ask),
            AgentLearningState.Ready(listOf(encoding), LearningApproval.Ask),
        )
        spec.assertTransition(
            AgentLearningState.Loading,
            AgentLearningIntent.Internal.LoadFailed,
            AgentLearningState.Failed,
            outputs = listOf(AgentLearningOutput.StorageFailed),
        )
        spec.assertIgnored(AgentLearningState.Failed, AgentLearningIntent.Public.Delete(encoding.id))
        spec.assertTransition(
            AgentLearningState.Failed,
            AgentLearningIntent.Public.Reload,
            AgentLearningState.Loading,
            effects = listOf(AgentLearningEffect.Load),
        )
    }

    @Test
    fun `learn before the registry is known is refused`() {
        for (state in listOf(AgentLearningState.Idle, AgentLearningState.Loading, AgentLearningState.Failed)) {
            spec.assertTransition(
                state,
                AgentLearningIntent.Public.Learn("r", instruction("2", "Other")),
                state,
                outputs = listOf(AgentLearningOutput.Rejected("r", LearnRejection.Unavailable)),
            )
        }
    }

    @Test
    fun `learn adds a new lesson and confirms it once written`() {
        val skill = instruction("2", "Release notes", InstructionKind.Skill)
        val updated = ready.copy(instructions = listOf(encoding, skill), revision = 4)
        val receipt = LearnReceipt("r", skill.id)
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Learn("r", skill),
            updated,
            effects = listOf(AgentLearningEffect.Persist(updated.instructions, 4, receipt)),
        )
        spec.assertTransition(
            updated,
            AgentLearningIntent.Internal.Saved(receipt),
            updated,
            outputs = listOf(AgentLearningOutput.Learned("r", skill.id)),
        )
        val persist = AgentLearningEffect.Persist(updated.instructions, 4, receipt)
        assertEquals(
            AgentLearningIntent.Internal.SaveFailed(receipt),
            spec.onEffectFailure(persist, IllegalStateException()),
        )
    }

    @Test
    fun `a learned lesson that could not be written is rolled back and rejected`() {
        val skill = instruction("2", "Release notes", InstructionKind.Skill)
        val updated = ready.copy(instructions = listOf(encoding, skill), revision = 4)
        val receipt = LearnReceipt("r", skill.id)
        val notPersisted = listOf(
            AgentLearningOutput.StorageFailed,
            AgentLearningOutput.Rejected("r", LearnRejection.NotPersisted),
        )
        spec.assertTransition(
            updated,
            AgentLearningIntent.Internal.SaveFailed(receipt),
            ready.copy(revision = 5),
            effects = listOf(AgentLearningEffect.Persist(listOf(encoding), 5)),
            outputs = notPersisted,
        )
        // The lesson is gone already (removed by the user): nothing is left to roll back.
        spec.assertTransition(ready, AgentLearningIntent.Internal.SaveFailed(receipt), ready, outputs = notPersisted)
        // After the rollback the agent may remember the same lesson again.
        spec.assertTransition(
            ready.copy(revision = 5),
            AgentLearningIntent.Public.Learn("again", skill),
            updated.copy(revision = 6),
            effects = listOf(AgentLearningEffect.Persist(updated.instructions, 6, LearnReceipt("again", skill.id))),
        )
    }

    @Test
    fun `a lesson is trimmed and refused when a text is empty or too long`() {
        val padded = instruction("2", "  Line endings ").copy(content = " Use LF \n", description = " ")
        val trimmed = padded.copy(title = "Line endings", content = "Use LF", description = "")
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Learn("r", padded),
            ready.copy(instructions = listOf(encoding, trimmed), revision = 4),
            effects = listOf(AgentLearningEffect.Persist(listOf(encoding, trimmed), 4, LearnReceipt("r", padded.id))),
        )
        val invalid = listOf(
            instruction("3", "x".repeat(LearningLimits.TITLE + 1)),
            instruction("4", "Long").copy(content = "x".repeat(LearningLimits.CONTENT + 1)),
            instruction("5", "Long description").copy(description = "x".repeat(LearningLimits.DESCRIPTION + 1)),
            instruction("6", "Skill without description", InstructionKind.Skill).copy(description = "  "),
        )
        for (lesson in invalid) {
            spec.assertTransition(
                ready,
                AgentLearningIntent.Public.Learn("r", lesson),
                ready,
                outputs = listOf(AgentLearningOutput.Rejected("r", LearnRejection.Invalid)),
            )
        }
        // Padding does not count against the limits.
        val fits = instruction("7", "Fits").copy(content = "x".repeat(LearningLimits.CONTENT))
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Learn("r", fits.copy(content = "   ${fits.content} ")),
            ready.copy(instructions = listOf(encoding, fits), revision = 4),
            effects = listOf(AgentLearningEffect.Persist(listOf(encoding, fits), 4, LearnReceipt("r", fits.id))),
        )
    }

    @Test
    fun `duplicate lessons and a full registry are rejected without writing`() {
        val duplicate = instruction("2", "  use utf-8 CONSOLE ")
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Learn("r", duplicate),
            ready,
            outputs = listOf(AgentLearningOutput.Rejected("r", LearnRejection.Duplicate)),
        )
        val otherProject = duplicate.copy(project = null)
        val stored = otherProject.copy(title = otherProject.title.trim(), content = otherProject.content.trim())
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Learn("r", otherProject),
            ready.copy(instructions = listOf(encoding, stored), revision = 4),
            effects = listOf(
                AgentLearningEffect.Persist(listOf(encoding, stored), 4, LearnReceipt("r", otherProject.id)),
            ),
        )
        val full = AgentLearningState.Ready(List(LearningLimits.INSTRUCTIONS) { instruction("$it", "Lesson $it") })
        spec.assertTransition(
            full,
            AgentLearningIntent.Public.Learn("r", instruction("new", "New lesson")),
            full,
            outputs = listOf(AgentLearningOutput.Rejected("r", LearnRejection.Limit)),
        )
    }

    @Test
    fun `switch, edit and delete persist a new revision`() {
        val disabled = ready.copy(instructions = listOf(encoding.copy(isEnabled = false)), revision = 4)
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.SetEnabled(encoding.id, false),
            disabled,
            effects = listOf(AgentLearningEffect.Persist(disabled.instructions, 4)),
        )
        spec.assertIgnored(ready, AgentLearningIntent.Public.SetEnabled(encoding.id, true))
        spec.assertIgnored(ready, AgentLearningIntent.Public.SetEnabled(InstructionId("missing"), false))

        val edited = encoding.copy(title = "UTF-8", content = "Run chcp 65001 first", updatedAtMillis = 9)
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Edit(encoding.id, " UTF-8 ", "", "Run chcp 65001 first ", 9),
            ready.copy(instructions = listOf(edited), revision = 4),
            effects = listOf(AgentLearningEffect.Persist(listOf(edited), 4)),
        )
        spec.assertIgnored(ready, AgentLearningIntent.Public.Edit(encoding.id, "", "", "text", 9))
        spec.assertIgnored(
            ready,
            AgentLearningIntent.Public.Edit(encoding.id, encoding.title, "", "x".repeat(LearningLimits.CONTENT + 1), 9),
        )

        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Delete(encoding.id),
            ready.copy(instructions = emptyList(), revision = 4),
            effects = listOf(AgentLearningEffect.Persist(emptyList(), 4)),
        )
        spec.assertIgnored(ready, AgentLearningIntent.Public.Delete(InstructionId("missing")))
    }

    @Test
    fun `an edit without changes is accepted without writing`() {
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Edit(encoding.id, encoding.title, encoding.description, encoding.content, 9),
            ready,
        )
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.Edit(encoding.id, " ${encoding.title} ", " ", "${encoding.content}\n", 9),
            ready,
        )
        spec.assertIgnored(ready, AgentLearningIntent.Public.Edit(InstructionId("missing"), "Title", "", "Text", 9))
    }

    @Test
    fun `an edit cannot erase the description of a skill`() {
        val skill = instruction("2", "Release", InstructionKind.Skill).copy(description = "When releasing")
        val registry = ready.copy(instructions = listOf(encoding, skill))
        spec.assertIgnored(registry, AgentLearningIntent.Public.Edit(skill.id, skill.title, "  ", skill.content, 9))
        val renamed = skill.copy(title = "Publish", updatedAtMillis = 9)
        spec.assertTransition(
            registry,
            AgentLearningIntent.Public.Edit(skill.id, "Publish", skill.description, skill.content, 9),
            registry.copy(instructions = listOf(encoding, renamed), revision = 4),
            effects = listOf(AgentLearningEffect.Persist(listOf(encoding, renamed), 4)),
        )
    }

    @Test
    fun `approval level is stored separately and save failures are reported`() {
        spec.assertTransition(
            ready,
            AgentLearningIntent.Public.SetApproval(LearningApproval.AcceptAll),
            ready.copy(approval = LearningApproval.AcceptAll, approvalRevision = 1),
            effects = listOf(AgentLearningEffect.PersistApproval(LearningApproval.AcceptAll, revision = 1)),
        )
        spec.assertIgnored(ready, AgentLearningIntent.Public.SetApproval(LearningApproval.Ask))
        spec.assertTransition(
            ready,
            AgentLearningIntent.Internal.SaveFailed(),
            ready,
            outputs = listOf(AgentLearningOutput.StorageFailed),
        )
        assertEquals(
            AgentLearningIntent.Internal.SaveFailed(),
            spec.onEffectFailure(AgentLearningEffect.PersistApproval(LearningApproval.Ask), IllegalStateException()),
        )
        assertEquals(
            AgentLearningIntent.Internal.LoadFailed,
            spec.onEffectFailure(AgentLearningEffect.Load, IllegalStateException()),
        )
    }

    @Test
    fun `approval levels decide by the agent's safety rating`() {
        assertTrue(LearningApproval.Ask.requiresDecision(isSafe = true))
        assertFalse(LearningApproval.Automatic.requiresDecision(isSafe = true))
        assertTrue(LearningApproval.Automatic.requiresDecision(isSafe = false))
        assertFalse(LearningApproval.AcceptAll.requiresDecision(isSafe = false))
    }

    @Test
    fun `model instructions require a model scope and only they carry one`() {
        val scope = ModelScope(EngineId("codex"), ModelId("gpt"))
        instruction("m", "Model hint", InstructionKind.Model, scope)
        assertFailsWith<IllegalArgumentException> { instruction("m", "Model hint", InstructionKind.Model) }
        assertFailsWith<IllegalArgumentException> { instruction("g", "General", InstructionKind.General, scope) }
    }

    private fun instruction(
        id: String,
        title: String,
        kind: InstructionKind = InstructionKind.General,
        scope: ModelScope? = null,
    ) = LearnedInstruction(
        InstructionId(id),
        kind,
        project,
        title,
        "Content of $title",
        description = if (kind == InstructionKind.Skill) "When $title applies" else "",
        modelScope = scope,
    )
}
