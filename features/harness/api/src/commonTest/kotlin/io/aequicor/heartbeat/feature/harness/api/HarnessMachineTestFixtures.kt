package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlin.test.assertNotNull
import kotlin.time.Instant

internal val request = RequestId("request")
internal val now = Instant.fromEpochMilliseconds(1_000)
internal val session = SessionRef(EngineId("engine"), SessionSourceId("source"), "session")
internal val code = HarnessItem.Script(ItemId("script"), ItemName("script"), "", "hooks {}")
internal val draft = HarnessDraft(HarnessName("test"), "Test", items = listOf(code))
internal val harness = Harness(
    HarnessId("harness"), draft.name, draft.title, "", HarnessScope.Attached, true,
    listOf(code), ToolPolicySpec(), null, 0, now, now,
)
internal val create = HarnessIntent.Public.Create(request, harness.id, draft, null, now)
internal val ready = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)

internal fun HarnessState.Ready.send(intent: HarnessIntent): HarnessState.Ready =
    assertNotNull(HarnessMachineSpec.resolve(this, intent)).to as HarnessState.Ready

internal fun HarnessState.Ready.refuses(intent: HarnessIntent.Public, reason: HarnessRejection) {
    HarnessMachineSpec.assertTransition(
        this,
        intent,
        this,
        outputs = listOf(HarnessOutput.Rejected(intent.requestId, reason)),
    )
}
internal fun saveOf(state: HarnessState.Ready): HarnessMutation.Save =
    state.pending.values.single() as HarnessMutation.Save
