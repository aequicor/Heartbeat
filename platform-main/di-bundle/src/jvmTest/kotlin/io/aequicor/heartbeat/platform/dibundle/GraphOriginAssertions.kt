package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledTaskHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Causal bookkeeping precedes every real graph native send, including helpers without a helper marker. */
internal suspend fun CoroutineScope.assertGraphOrigins(services: AiEngineTestAccessors) {
    val runtime = services.studioRuntime
    val parent = services.studioRepository.createSession(null, "Parent")
    val accepted = CompletableDeferred<Unit>()
    val parentRun = async {
        runtime.run(parent.id, "Parent", runtime.defaults(), emptyList()) { accepted.complete(Unit) }
    }
    accepted.await()
    val engine = TestAdapter.runtimes.single()
    val parentNative = engine.natives.single()
    parentNative.finish()
    assertEquals(RunOutcome.Completed, parentRun.await())
    val target = requireNotNull(services.modelSelections.observe().first().defaultTarget)
    val host = services.scheduledSessionHosts.filterIsInstance<ScheduledTaskHost>().maxBy { it.priority }
    val causes = setOf(RequestInitiator(parentNative.ref, RequestId("source")))
    val observer = (services as RequestOriginTestAccessors).requestOriginObserver
    for (mode in listOf("cancel", "failure", "send")) {
        val request = SpawnRequest(
            parentNative.ref,
            null,
            target,
            "Graph helper",
            WakePrompt(RequestId(mode), "Work", "Assignment"),
            causes,
        )
        val chat = checkNotNull(host.prepareTask(request))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        observer.beforeRecord = {
            assertEquals(request.prompt.request, it.request)
            assertEquals(causes, it.causes)
            assertNotEquals(parentNative.ref, it.session)
            assertEquals(engine.natives.last().ref, it.session)
            assertTrue(engine.natives.last().sent.isEmpty())
            entered.complete(Unit)
            release.await()
            check(mode != "failure") { "Origin storage unavailable" }
        }
        val run = async { host.runTask(chat, request, null, flowOf(true)) }
        entered.await()
        val native = engine.natives.last()
        assertTrue(native.sent.isEmpty())
        assertFalse(run.isCompleted)
        if (mode == "cancel") {
            run.cancelAndJoin()
            release.complete(Unit)
        } else {
            release.complete(Unit)
            if (mode == "send") {
                services.studioRepository.observeMessages(
                    chat,
                ).first { messages -> messages.any { it is StudioMessage.Prompt } }
                assertEquals(listOf(request.prompt.request), native.sent.map { it.id })
                native.finish()
            }
            run.await()
        }
        assertEquals(if (mode == "send") 1 else 0, native.sent.size)
    }
}
