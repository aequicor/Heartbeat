package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlResult
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlEffect
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** Profile services the control panel works through. */
internal data class EngineControlServices(
    val management: EngineManagement,
    val configuration: EngineConfiguration,
    val workspaces: LocalWorkspaces,
)

/**
 * Observes engine management, engine configuration and the saved projects, and executes the panel's operations.
 * Writes complete even if the panel closes meanwhile; secrets of a configuration edit are closed once it ran.
 */
internal class EngineControlEffects(private val services: EngineControlServices) :
    EffectHandler<EngineControlEffect, EngineControlIntent> {
    private val log = Log.tag("EngineControlEffects")

    override suspend fun handle(effect: EngineControlEffect, machine: EffectScope<EngineControlIntent>) {
        when (effect) {
            EngineControlEffect.Observe -> snapshots().collect {
                machine.send(
                    EngineControlIntent.Internal.Snapshot(it),
                )
            }

            is EngineControlEffect.Execute -> {
                val result = withContext(NonCancellable) { execute(effect.operation) }
                machine.send(EngineControlIntent.Internal.Applied(result))
            }
        }
    }

    private fun snapshots(): Flow<ControlSnapshot> = combine(
        services.management.state,
        services.configuration.state,
        projects(),
        ::ControlSnapshot,
    )

    private fun projects(): Flow<List<LocalWorkspace>> =
        if (services.workspaces.isAvailable) services.workspaces.observe() else flowOf(emptyList())

    private suspend fun execute(operation: ControlOperation): ControlResult {
        val engine = operation.engine
        log.i { "control operation ${operation::class.simpleName.orEmpty()} engine=${engine.value}" }
        val configuration = services.configuration
        return when (operation) {
            is ControlOperation.Manage -> {
                services.management.execute(engine, operation.command)
                ControlResult.Done
            }

            is ControlOperation.SaveProfile -> try {
                configuration.saveProfile(engine, operation.edit, operation.expected)
                ControlResult.Done
            } finally {
                operation.edit.secrets.values.forEach { it?.close() }
            }

            is ControlOperation.ListResources ->
                ControlResult.Resources(configuration.resources(engine, operation.workspace))

            is ControlOperation.OpenProject ->
                ControlResult.Project(configuration.readProject(engine, operation.workspace))

            is ControlOperation.SaveProject -> ControlResult.Project(
                configuration.saveProject(engine, operation.workspace, operation.edit, operation.expected),
            )

            is ControlOperation.SetTrust -> {
                configuration.setProjectTrust(engine, operation.workspace, operation.trust)
                ControlResult.Done
            }

            is ControlOperation.PreviewImport -> ControlResult.Preview(configuration.previewImport(engine))

            is ControlOperation.Import -> ControlResult.Imported(
                configuration.importNative(engine, operation.selection, operation.expected),
            )
        }
    }
}
