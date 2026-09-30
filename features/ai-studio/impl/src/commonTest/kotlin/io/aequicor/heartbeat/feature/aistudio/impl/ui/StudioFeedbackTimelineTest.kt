package io.aequicor.heartbeat.feature.aistudio.impl.ui

import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbToolBlock
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FeedbackFailureUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FeedbackOutcomeUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FeedbackParameterUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.FeedbackUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioFeedbackTimelineTest {
    private val feedbackLabels = FeedbackLabels(
        model = "Model",
        effort = "Reasoning effort",
        trust = "Command approval",
        defaultValue = "Engine default",
        approvals = FeedbackApprovalLabels("Ask", "Allow edits", "Approve for me"),
        outcomes = FeedbackOutcomeLabels(
            pending = "Changing %1\$s: %2\$s…",
            appliedTool = "Session continues. From the next tool call: %1\$s — %2\$s.",
            appliedRequest = "Session continues. From the next model request: %1\$s — %2\$s.",
            failed = "Could not change %1\$s: %2\$s.",
            unknown = "The change to %1\$s could not be confirmed.",
            pendingDetail = "Waiting for the engine to confirm the change.",
            nextTool = "The session continues from the next tool call.",
            nextRequest = "The session continues from the next model request.",
            kept = "The current confirmed parameters are shown in the composer.",
            unknownDetail = "Check the current session settings.",
        ),
        failures = FeedbackFailureLabels(
            "unsupported change",
            "engine busy",
            "connection change",
            "invalid value",
            "access denied",
            "transport failed",
            "session unavailable",
            "unconfirmed",
        ),
    )
    private val labels = TimelineLabels(
        "Session",
        "You",
        "Agent",
        "Studio",
        "Stopped after %1\$s",
        FailureLabels("Failed"),
        DurationLabels("%1\$d s", "%1\$d min %2\$d s"),
        feedback = feedbackLabels,
    )

    @Test
    fun `confirmed trust is an agent tool with a readable summary and next tool boundary`() {
        val message = reply(
            FeedbackUi(FeedbackParameterUi.Trust, FeedbackOutcomeUi.Applied, approval = ApprovalUi.AutoApprove),
            ToolStatusUi.Done,
        ).toHb(labels)
        val tool = assertIs<HbMessagePart.Tool>(message.parts.single()).call

        assertEquals(HbChatRole.Assistant, message.role)
        assertEquals(HbMessageStatus.Complete, message.status)
        assertEquals("feedback:operation", tool.id)
        assertEquals("feedback", tool.title)
        assertEquals(HbToolStatus.Complete, tool.status)
        assertEquals("Session continues. From the next tool call: Command approval — Approve for me.", tool.summary)
        assertEquals(tool, message.toolCalls.single())
        assertTrue(assertIs<HbToolBlock.Markdown>(tool.blocks.single()).source.contains("next tool call"))
    }

    @Test
    fun `confirmed nullable effort shows engine default and the next model request boundary`() {
        val message = reply(
            FeedbackUi(FeedbackParameterUi.Effort, FeedbackOutcomeUi.Applied),
            ToolStatusUi.Done,
        ).toHb(labels)
        val tool = message.toolCalls.single()

        assertEquals("Session continues. From the next model request: Reasoning effort — Engine default.", tool.summary)
        assertTrue(assertIs<HbToolBlock.Markdown>(tool.blocks.single()).source.contains("next model request"))
    }

    @Test
    fun `rejected changes show the localized reason and confirmed session parameters`() {
        val message = reply(
            FeedbackUi(
                FeedbackParameterUi.Model,
                FeedbackOutcomeUi.Failed,
                value = "new-model",
                failure = FeedbackFailureUi.ConnectionChange,
            ),
            ToolStatusUi.Failed,
        ).toHb(labels)
        val tool = message.toolCalls.single()

        assertEquals(HbToolStatus.Error, tool.status)
        assertEquals("Could not change Model: connection change.", tool.summary)
        assertTrue(assertIs<HbToolBlock.Markdown>(tool.blocks.single()).source.contains("confirmed parameters"))
    }

    @Test
    fun `pending and restored unknown never claim successful application`() {
        val cache = TimelineCache()
        val pending = reply(
            FeedbackUi(FeedbackParameterUi.Model, FeedbackOutcomeUi.Pending, value = "model-b"),
            ToolStatusUi.Running,
        )
        val first = cache.update(listOf(pending), labels).messages.single().toolCalls.single()
        assertEquals("Changing Model: model-b…", first.summary)
        assertEquals(HbToolStatus.Running, first.status)

        val unknown = pending.copy(
            tools = persistentListOf(
                pending.tools.single().copy(
                    status = ToolStatusUi.Cancelled,
                    feedback = pending.tools.single().feedback!!.copy(outcome = FeedbackOutcomeUi.Unknown),
                ),
            ),
            parts = persistentListOf(),
        )
        val restored = cache.update(listOf(unknown), labels).messages.single().toolCalls.single()
        assertEquals(first.id, restored.id)
        assertEquals("The change to Model could not be confirmed.", restored.summary)
        assertEquals(HbToolStatus.Cancelled, restored.status)
    }

    @Test
    fun `changing locale rebuilds feedback text while keeping tool identity`() {
        val cache = TimelineCache()
        val messages = listOf(
            reply(
                FeedbackUi(FeedbackParameterUi.Model, FeedbackOutcomeUi.Applied, value = "model-b"),
                ToolStatusUi.Done,
            ),
        )
        val first = cache.update(messages, labels).messages.single().toolCalls.single()
        val translatedLabels = labels.copy(
            feedback = feedbackLabels.copy(
                model = "Модель",
                outcomes = feedbackLabels.outcomes.copy(
                    appliedRequest = "Сессия работает. Со следующего запроса к модели: «%1\$s» — %2\$s.",
                ),
            ),
        )
        val translated = cache.update(messages, translatedLabels).messages.single().toolCalls.single()

        assertEquals(first.id, translated.id)
        assertEquals("Сессия работает. Со следующего запроса к модели: «Модель» — model-b.", translated.summary)
    }

    @Test
    fun `model values remain literal in expanded Markdown and native console tools remain unchanged`() {
        val model = reply(
            FeedbackUi(FeedbackParameterUi.Model, FeedbackOutcomeUi.Applied, value = "model_[test]"),
            ToolStatusUi.Done,
        ).toHb(labels).toolCalls.single()
        assertTrue(assertIs<HbToolBlock.Markdown>(model.blocks.single()).source.contains("model\\_\\[test\\]"))

        val native = ToolUi("native", "shell", ToolStatusUi.Done, "literal *output*\n", null)
        val message = MessageUi.Reply(
            "answer",
            Instant.fromEpochSeconds(0),
            "",
            persistentListOf(native),
            false,
        ).toHb(labels)
        val tool = message.toolCalls.single()
        assertEquals("", tool.summary)
        assertEquals("literal *output*", assertIs<HbToolBlock.Console>(tool.blocks.single()).text)
    }

    private fun reply(feedback: FeedbackUi, status: ToolStatusUi): MessageUi.Reply {
        val tool = ToolUi("feedback:operation", "feedback", status, "", null, feedback)
        return MessageUi.Reply(
            "feedback:operation",
            Instant.fromEpochSeconds(0),
            "",
            persistentListOf(tool),
            false,
            parts = persistentListOf(ReplyPartUi.Tool(tool)),
        )
    }
}
