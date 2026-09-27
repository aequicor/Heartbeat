package io.aequicor.heartbeat.feature.aisessionenginetransfer.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlin.time.Instant

internal val TestSource = SessionRef(EngineId("codex"), SessionSourceId("codex-local"), "source-session")
internal val TestTarget = EngineTarget(EngineId("claude"), EngineBindingId("binding"), ModelId("model"))
internal val TestRequest = TransferRequest(TransferId("transfer"), TestSource, TestTarget)
internal val TestConversation = LogicalConversation(
    ConversationId("conversation"),
    listOf(ConversationSegment(TestSource, Instant.fromEpochSeconds(1))),
)
internal val TestHandoffPrompt = PromptRequest(RequestId("handoff"), listOf(ContentPart.Text("private transcript")))
internal val TestSegment = ConversationSegment(
    ref = SessionRef(TestTarget.engine, SessionSourceId("claude-local"), "target-session"),
    startedAt = Instant.fromEpochSeconds(2),
    target = TestTarget,
    handoff = Handoff(TestSource, TestHandoffPrompt.id, HandoffStatus.Accepted),
)
