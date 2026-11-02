package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StudioRecoveryPayloadTest {
    private val source = RequestId("source")
    private val request = StudioTurnRequest(
        "chat",
        "original assignment",
        RunSettings("model", ReasoningEffort.Medium, ApprovalMode.Ask),
        WorktreeRunKind.Coding,
        RequestId("execution"),
        recoveryRequests = listOf(source),
        origin = StudioTurnOrigin.GraphWork(source),
    )

    @Test
    fun `callbacks preserve payload identity but changed work cannot reuse it`() {
        val first = request.recoveryPayloadFingerprint()
        assertEquals(first, request.copy(onOutcome = {}).recoveryPayloadFingerprint())
        assertEquals(
            first,
            request.copy(recoveryCheckpoint = "new cursor", recoveryRequests = listOf(source, request.request))
                .recoveryPayloadFingerprint(),
        )
        val changed = listOf(
            request.copy(prompt = "another task"),
            request.copy(settings = request.settings.copy(modelId = "other-model")),
            request.copy(settings = request.settings.copy(approval = ApprovalMode.AutoApprove)),
            request.copy(directives = listOf("new authority")),
            request.copy(continuationOf = RequestId("unrelated")),
            request.copy(origin = StudioTurnOrigin.Conversation),
            request.copy(compaction = ContextCompaction()),
            request.copy(isRollover = true),
        )
        for (other in changed) assertNotEquals(first, other.recoveryPayloadFingerprint())
    }

    @Test
    fun `directive boundaries and compaction contents are not collapsed by redacted strings`() {
        assertNotEquals(
            request.copy(directives = listOf("a", "bc")).recoveryPayloadFingerprint(),
            request.copy(directives = listOf("ab", "c")).recoveryPayloadFingerprint(),
        )
        assertNotEquals(
            request.copy(compaction = ContextCompaction("first")).recoveryPayloadFingerprint(),
            request.copy(compaction = ContextCompaction("other")).recoveryPayloadFingerprint(),
        )
    }
}
