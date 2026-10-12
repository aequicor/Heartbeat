package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioGraphResultTest {
    @Test
    fun `partial output never replaces native failure evidence`() {
        val failure = EngineFailure.Unknown()
        val result = TurnOutcome.Failed(failure).graphResult("partial answer ".repeat(1000))

        assertEquals(GraphTaskPhase.Failed, result.phase)
        assertTrue(result.text.startsWith("Agent turn failed: ${failure.code}\n\npartial answer"))
        assertEquals(SchedulerLimits.MAX_PAYLOAD, result.text.length)
    }

    @Test
    fun `finished text cannot turn an unconfirmed native outcome into success`() {
        val result = TurnOutcome.Unknown.graphResult("finished")

        assertEquals(GraphTaskPhase.RecoveryRequired, result.phase)
        assertEquals("Native task outcome is not confirmed\n\nfinished", result.text)
        assertEquals("answer", TurnOutcome.Completed.graphResult("answer").text)
    }

    @Test
    fun `a graph assignment takes the approval of its parent chat, also after the parent rolled over`() {
        val old = SessionRef(EngineId("pi"), SessionSourceId("profile"), "old")
        val parent = StudioChatRecord(
            "parent",
            "Parent",
            Instant.DISTANT_PAST,
            ref = old.copy(nativeId = "new"),
            previousRefs = listOf(old),
            configuration = StudioSessionSettings("model", null, ApprovalMode.Ask),
        )
        val edits = StudioSessionSettings("model", null, ApprovalMode.AutoEdits)
        val confirmed = mapOf("parent" to StudioSessionConfiguration(edits))
        val base = DefaultRunSettings.copy(approval = ApprovalMode.AutoApprove)

        assertEquals(ApprovalMode.Ask, listOf(parent).graphDefaults(old, emptyMap(), base).approval)
        assertEquals(ApprovalMode.AutoEdits, listOf(parent).graphDefaults(old, confirmed, base).approval)
        assertEquals(base, listOf(parent).graphDefaults(old.copy(nativeId = "other"), confirmed, base))
    }
}
