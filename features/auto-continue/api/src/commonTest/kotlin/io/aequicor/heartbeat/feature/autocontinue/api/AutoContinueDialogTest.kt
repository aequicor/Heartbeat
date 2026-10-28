package io.aequicor.heartbeat.feature.autocontinue.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoContinueDialogTest {
    private var position = 0L

    private fun info(id: String) = ItemInfo(ItemId(id), position++, revision = 0)

    private fun message(id: String, role: MessageRole, vararg parts: ContentPart) =
        SessionItem.Message(info(id), role, parts.toList())

    @Test
    fun `dialog keeps user messages and the last agent text of each turn`() {
        val items = listOf(
            message("u1", MessageRole.User, ContentPart.Text("Сделай фичу")),
            message("a1", MessageRole.Assistant, ContentPart.Text("Смотрю код")),
            SessionItem.ToolCall(info("t1"), ToolCallId("c1"), "read_file", "{}", ToolCallStatus.Succeeded),
            message(
                "a2",
                MessageRole.Assistant,
                ContentPart.Reasoning("думаю"),
                ContentPart.Text("Шаг 1 готов"),
            ),
            message("u2", MessageRole.User, ContentPart.Text("Продолжай")),
            message("s1", MessageRole.System, ContentPart.Text("system")),
            message("a3", MessageRole.Assistant, ContentPart.Text(" ")),
            message("a4", MessageRole.Assistant, ContentPart.Text("Шаг 2 готов")),
        )
        assertEquals(
            listOf(
                DialogEntry(DialogAuthor.Human, "Сделай фичу"),
                DialogEntry(DialogAuthor.Agent, "Шаг 1 готов"),
                DialogEntry(DialogAuthor.Autopilot, "Продолжай"),
                DialogEntry(DialogAuthor.Agent, "Шаг 2 готов"),
            ),
            dialogOf(items.reversed(), autopilotItems = setOf(ItemId("u2"))),
        )
    }

    @Test
    fun `near repeats ignore case and punctuation`() {
        assertFalse(isNearRepeat(null, "Продолжай"))
        assertTrue(isNearRepeat("Продолжай, пожалуйста!", "продолжай пожалуйста"))
        assertFalse(isNearRepeat("Продолжай со шага 2", "Продолжай со шага 3"))
        assertFalse(isNearRepeat("Запусти тесты", "Исправь detekt"))
    }
}
