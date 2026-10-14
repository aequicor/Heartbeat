package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsMachineKey
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the settings removal against a live studio turn and checks both consumer projections. */
internal suspend fun CoroutineScope.assertProviderRemovalDuringExecution(
    services: AiEngineTestAccessors,
    machines: MachineRegistry,
) {
    val repository = services.studioRepository
    val runtime = services.studioRuntime
    val binding = services.engineFacade.bindings.state.first { it.isNotEmpty() }.single()
    val settings = runtime.defaults()
    assertEquals(settings.modelId, repository.observeModels().first { it.isNotEmpty() }.single().id)
    val chat = repository.createSession(null, "Remove provider during execution")
    val run = startAcceptedRun(runtime, chat.id, "Run until the provider is removed", settings)
    val engineRuntime = TestAdapter.runtimes.single()
    val native = engineRuntime.natives.single()
    assertIs<ActiveSessionState.Running>(native.state.value)
    val connections = checkNotNull(machines.find(EngineConnectionsMachineKey))
    connections.state.first { it is EngineConnectionsState.Active && it.pending == null }

    assertEquals(
        SendResult.Accepted,
        machines.send(
            EngineConnectionsMachineKey,
            EngineConnectionsIntent.Public.Apply(ConnectionOperation.Disconnect(binding.id)),
        ),
    )

    val removed = connections.state.first { state ->
        if (state !is EngineConnectionsState.Active || state.pending != null) return@first false
        state.failed != null || state.snapshot?.let { snapshot ->
            snapshot.engines.flatMap { it.bindings }.none { it.id == binding.id } &&
                snapshot.sources.none { it.info.id == binding.authSource } &&
                snapshot.selection.bindings.none { it.binding == binding.id }
        } == true
    } as EngineConnectionsState.Active
    assertNull(removed.failed)
    assertEquals(1, engineRuntime.closes.value)
    assertEquals(ActiveSessionState.Closed, native.state.value)
    assertTrue(services.engineFacade.bindings.state.first { it.isEmpty() }.isEmpty())
    assertTrue(repository.observeModels().first { it.isEmpty() }.isEmpty())
    assertNull(services.modelSelections.observe().first().defaultTarget)
    assertNull(services.engineAuthSources.get(binding.authSource))
    assertEquals(RunOutcome.Failed, run.await())
    assertTrue(runtime.state.value.running.isEmpty())
    assertIs<StudioMessage.Prompt>(repository.observeMessages(chat.id).first().first())
}

/** Native history can publish inside send before the facade acknowledges ownership of its turn. */
internal suspend fun CoroutineScope.startAcceptedRun(
    runtime: StudioRuntime,
    sessionId: String,
    prompt: String,
    settings: RunSettings,
): Deferred<RunOutcome> {
    val accepted = CompletableDeferred<Unit>()
    val result = async {
        runtime.run(sessionId, prompt, settings, emptyList()) { accepted.complete(Unit) }
    }
    select {
        accepted.onAwait { }
        result.onAwait { outcome ->
            error("Run completed before native acceptance: $outcome")
        }
    }
    return result
}
