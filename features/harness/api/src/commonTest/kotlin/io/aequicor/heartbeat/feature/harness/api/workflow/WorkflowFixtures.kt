package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

internal val Now = Instant.parse("2026-10-06T00:00:00Z")
internal val Caller = SessionRef(EngineId("pi"), SessionSourceId("source"), "caller")
internal val Request = RequestId("request")
internal val Digest = "a".repeat(64)
internal const val SECRET = "private workflow text"

internal fun workflowRun(id: String = "wf_one"): WorkflowRun = WorkflowRun(
    RunId(id), HarnessId("harness"), ItemId("workflow"),
    PinnedWorkflow(SECRET, Digest, 2, mapOf(ItemName("skill") to SECRET)),
    JsonObject(emptyMap()), Caller, WorkflowOrigin.Agent, Now, Now + 6.hours,
)

internal fun preparedStep(): WorkflowStep = WorkflowStep(StepKey("p0/s0"), Digest)
