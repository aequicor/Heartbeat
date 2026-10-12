package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDeferredException
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant

/** A direct graph notification shares the native causal barrier while retaining its original inbox identity. */
internal suspend fun CoroutineScope.assertGraphWakeOrigins(services: AiEngineTestAccessors) {
    val runtime = services.studioRuntime
    val chat = services.studioRepository.createSession(null, "Owner")
    val accepted = CompletableDeferred<Unit>()
    val run = async { runtime.run(chat.id, "Start", runtime.defaults(), emptyList()) { accepted.complete(Unit) } }
    accepted.await()
    val native = TestAdapter.runtimes.single().natives.single()
    native.finish()
    assertEquals(RunOutcome.Completed, run.await())
    val host = services.scheduledSessionHosts.maxBy { it.priority }
    val request = WakeRequest(
        WakeId("done_legacy"),
        native.ref,
        null,
        WakeCondition(deadline = Instant.fromEpochSeconds(0)),
        "Finished",
        WakeOrigin.Feature("scheduler.task_graphs"),
        isDeduplicationRequired = true,
    )
    val admission = MutableStateFlow(ScheduledWakeAdmission.Allow)
    val causes = setOf(RequestInitiator(native.ref, RequestId("initial-R")))
    val prompt = WakePrompt(
        RequestId("done_legacy"),
        "Finished",
        "Inspect results",
        isDeduplicationRequired = true,
        admission = admission,
        causes = causes,
    )
    val observer = (services as RequestOriginTestAccessors).requestOriginObserver
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    observer.beforeRecord = {
        assertEquals(native.ref, it.session)
        assertEquals(prompt.request, it.request)
        assertEquals(causes, it.causes)
        entered.complete(Unit)
        release.await()
    }
    val paused = async { assertFailsWith<ScheduledWakeDeferredException> { host.wake(request, prompt) } }
    entered.await()
    assertEquals(1, native.sent.size)
    admission.value = ScheduledWakeAdmission.Defer
    paused.await()
    assertEquals(1, native.sent.size)
    release.complete(Unit)
    admission.value = ScheduledWakeAdmission.Allow
    host.wake(request, prompt)
    assertEquals(listOf(prompt.request), native.sent.drop(1).map { it.id })
    observer.beforeRecord = { error("accepted duplicate must not prepare again") }
    host.wake(request, prompt)
    assertEquals(2, native.sent.size)
    native.finish()
    runtime.state.first { chat.id !in it.running }
    assertFalse(chat.id in runtime.state.value.running)
}
