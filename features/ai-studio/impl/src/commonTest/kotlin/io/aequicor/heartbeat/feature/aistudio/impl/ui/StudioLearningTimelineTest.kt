package io.aequicor.heartbeat.feature.aistudio.impl.ui

import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbToolBlock
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.LearningCallUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.LearningKindUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class StudioLearningTimelineTest {
    private val labels = TimelineLabels(
        "Session",
        "You",
        "Agent",
        "Studio",
        "Stopped after %1\$s",
        FailureLabels("Failed"),
        DurationLabels("%1\$d s", "%1\$d min %2\$d s"),
        learning = LearningLabels("New instruction", "Learned skill", "General", "Model", "Skill"),
    )

    @Test
    fun `remember call is a card with the instruction instead of raw arguments`() {
        val call = LearningCallUi(isSkillLoad = false, LearningKindUi.General, "UTF-8 console", "Run chcp 65001")
        val tool = reply(
            ToolUi("call", "mcp__heartbeat_tools__remember", ToolStatusUi.Done, "Saved.", null, null, call),
        )
            .toHb(labels)
            .let { assertIs<HbMessagePart.Tool>(it.parts.single()).call }

        assertEquals("New instruction", tool.title)
        assertEquals("General · UTF-8 console", tool.summary)
        assertEquals(HbToolStatus.Complete, tool.status)
        assertEquals("Run chcp 65001", assertIs<HbToolBlock.Console>(tool.blocks[0]).text)
        assertEquals("Saved.", assertIs<HbToolBlock.Console>(tool.blocks[1]).text)
    }

    @Test
    fun `skill load names the skill and other tools keep their names`() {
        val skill = LearningCallUi(isSkillLoad = true, null, "Release", "")
        val loaded = reply(ToolUi("call", "load_learned_skill", ToolStatusUi.Running, "", null, null, skill))
            .toHb(labels).toolCalls.single()
        assertEquals("Learned skill", loaded.title)
        assertEquals("Release", loaded.summary)

        val plain = reply(ToolUi("call", "run_command", ToolStatusUi.Done, "ok", null)).toHb(labels).toolCalls.single()
        assertEquals("run_command", plain.title)
        assertEquals("", plain.summary)
    }

    private fun reply(tool: ToolUi) = MessageUi.Reply(
        "reply",
        Instant.fromEpochSeconds(0),
        "",
        persistentListOf(tool),
        false,
        parts = persistentListOf(ReplyPartUi.Tool(tool)),
    )
}
