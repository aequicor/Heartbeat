package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryClock
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryTestStore
import io.aequicor.heartbeat.feature.harness.impl.data.run.KeyValueHarnessRunStorage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration.Companion.hours

/** Exercises replay against the production journal and KV monotonicity rules, without compilation or agents. */
internal class WorkflowFixture {
    val clock = RunDeliveryClock()
    val store = RunDeliveryTestStore(clock)
    val storage = KeyValueHarnessRunStorage(store, clock)
    val initial = WorkflowRun(
        RunId("wf_test"), HarnessId("harness"), ItemId("workflow"),
        PinnedWorkflow(
            "workflow-source", "a".repeat(64), 1,
            mapOf(ItemName("greeting") to "Hello {{name}}", ItemName("guide") to "Pinned skill"),
        ),
        JsonObject(emptyMap()), null, WorkflowOrigin.Script, clock.now(), clock.now() + 1.hours,
    )
    val feedback = mutableListOf<WorkflowStep>()
    val requests = mutableListOf<Pair<StepKey, String>>()

    suspend fun journal(): WorkflowRunJournal {
        if (storage.load().isEmpty()) check(storage.save(initial, null))
        return WorkflowRunJournal(storage.load().single(), storage) { feedback += it }
    }

    suspend fun engine(): WorkflowEngine {
        val journal = journal()
        val agents = WorkflowAgentSteps { key, digest, prompt, _ ->
            journal.prepare(key, digest)
            requests += key to prompt
            val result = "Answer: $prompt"
            journal.complete(key, JsonPrimitive(result))
            result
        }
        return WorkflowEngine(journal.state.value, journal, agents, clock) { it.encodeUtf8().sha256().hex() }
    }
}
