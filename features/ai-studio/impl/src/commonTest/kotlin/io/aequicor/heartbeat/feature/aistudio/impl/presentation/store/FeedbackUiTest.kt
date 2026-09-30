package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import io.aequicor.heartbeat.feature.feedback.api.FeedbackChange
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class FeedbackUiTest {
    private val target = EngineTarget(EngineId("pi"), EngineBindingId("route"), ModelId("old"))

    @Test
    fun `successful feedback uses confirmed model effort and approval values`() {
        val actual = SessionConfiguration(ModelId("actual"), null, TrustLevel.AutoEdits)
        val applied = FeedbackOutcome.Applied(actual)
        assertEquals(
            FeedbackUi(FeedbackParameterUi.Model, FeedbackOutcomeUi.Applied, "actual"),
            record(FeedbackChange.Model(target, target.copy(model = ModelId("requested"))), applied).toUi(),
        )
        assertEquals(
            FeedbackUi(FeedbackParameterUi.Effort, FeedbackOutcomeUi.Applied, null),
            record(FeedbackChange.Effort("high", "xhigh"), applied).toUi(),
        )
        assertEquals(
            FeedbackUi(FeedbackParameterUi.Trust, FeedbackOutcomeUi.Applied, approval = ApprovalUi.AutoEdits),
            record(FeedbackChange.Trust(TrustLevel.Ask, TrustLevel.Full), applied).toUi(),
        )
    }

    @Test
    fun `pending and interrupted feedback preserve the request without inferring acknowledgment`() {
        assertEquals(
            FeedbackUi(FeedbackParameterUi.Effort, FeedbackOutcomeUi.Pending, "high"),
            record(FeedbackChange.Effort(null, "high"), FeedbackOutcome.Pending).toUi(),
        )
        assertEquals(
            FeedbackUi(FeedbackParameterUi.Trust, FeedbackOutcomeUi.Unknown, approval = ApprovalUi.AutoApprove),
            record(FeedbackChange.Trust(TrustLevel.Ask, TrustLevel.Full), FeedbackOutcome.Unknown).toUi(),
        )
    }

    @Test
    fun `failed changes distinguish an unavailable capability from a busy session and a foreign route`() {
        val unsupported = FeedbackOutcome.Failed(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
        val busy = FeedbackOutcome.Failed(EngineFailure.Session(SessionFailureReason.Busy))
        assertEquals(
            FeedbackFailureUi.Unsupported,
            record(FeedbackChange.Trust(TrustLevel.Ask, TrustLevel.Full), unsupported).toUi().failure,
        )
        assertEquals(
            FeedbackFailureUi.Busy,
            record(FeedbackChange.Model(target, target.copy(model = ModelId("next"))), busy).toUi().failure,
        )
        assertEquals(
            FeedbackFailureUi.ConnectionChange,
            record(FeedbackChange.Model(target, target.copy(binding = EngineBindingId("other"))), busy).toUi().failure,
        )
    }

    @Test
    fun `feedback remains the same tool in legacy tools and ordered reply parts`() {
        val feedback = record(FeedbackChange.Effort("high", "low"), FeedbackOutcome.Pending)
        val tool = StudioToolRun("feedback-operation", "feedback", ToolRunStatus.Pending, feedback = feedback)
        val reply = StudioMessage.Reply(
            id = "reply",
            createdAt = Instant.fromEpochSeconds(1),
            tools = listOf(tool),
            parts = listOf(StudioReplyPart.Tool(tool)),
        )
        val ui = assertIs<MessageUi.Reply>(reply.toUi())
        val mapped = ui.tools.single()
        assertEquals("feedback-operation", mapped.id)
        assertEquals("feedback", mapped.title)
        assertEquals(feedback.toUi(), mapped.feedback)
        assertEquals(mapped, assertIs<ReplyPartUi.Tool>(ui.parts.single()).tool)
    }

    private fun record(change: FeedbackChange, outcome: FeedbackOutcome): FeedbackRecord = FeedbackRecord(
        id = "operation",
        source = "chat",
        revision = 0,
        createdAt = Instant.fromEpochSeconds(1),
        change = change,
        outcome = outcome,
    )
}
