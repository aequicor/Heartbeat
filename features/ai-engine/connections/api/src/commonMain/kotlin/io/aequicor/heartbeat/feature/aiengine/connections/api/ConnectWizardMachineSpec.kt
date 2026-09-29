package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.TransitionBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect.ObserveEngines
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent.Internal
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent.Public
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Cancelled
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.ChoosingEngine
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.ChoosingMethod
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.ChoosingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Connecting
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Finished
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Idle
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.RollingBack
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Saving
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo

/**
 * Wizard engine → authentication → models. The binding is created before model discovery, because models are only
 * resolvable through an exact binding; abandoning the wizard afterwards rolls the connection back.
 *
 * | From | Intent | Guard | To | Effect / Output |
 * |---|---|---|---|---|
 * | Idle | Start | | ChoosingEngine | ObserveEngines |
 * | ChoosingEngine | EnginesChanged | preselected engine connectable | ChoosingMethod | |
 * | ChoosingEngine | EnginesChanged | otherwise | stay (preselection dropped unless the catalog is empty) | |
 * | ChoosingEngine | EnginesFailed | | stay (failure) | |
 * | ChoosingEngine | Retry | has failure | ChoosingEngine (re-entry) | ObserveEngines |
 * | ChoosingEngine | ChooseEngine | engine connectable | ChoosingMethod | |
 * | ChoosingMethod | CheckConnection | accepts credential, no check running | stay (check Running) | CheckConnection |
 * | ChoosingMethod | ConnectionChecked | check running | stay (check result) | |
 * | ChoosingMethod | Connect | accepts credential, no check running | Connecting | Connect |
 * | ChoosingMethod | Back / Dismiss | | ChoosingEngine | ObserveEngines |
 * | Connecting | Connected | | ChoosingModels | DiscoverModels |
 * | Connecting | ConnectFailed | | ChoosingMethod (failure) | |
 * | ChoosingModels | ModelsLoaded / ModelsFailed | | stay | |
 * | ChoosingModels | ToggleModel / SelectAllModels | models discovered | stay | |
 * | ChoosingModels | Retry | discovery failed | stay (discovering) | DiscoverModels |
 * | ChoosingModels | Retry | saving failed | Saving | SaveModels |
 * | ChoosingModels | Finish | not discovering | Saving | SaveModels |
 * | ChoosingModels | Cancel / Dismiss | | RollingBack | Rollback |
 * | Saving | Saved | | Finished | Completed |
 * | Saving | SaveFailed | | ChoosingModels (failure) | |
 * | RollingBack | RolledBack | | Cancelled | Cancelled |
 * | Idle, ChoosingEngine, ChoosingMethod | Cancel | | Cancelled | Cancelled |
 * | Idle, ChoosingEngine | Dismiss | | Cancelled | Cancelled |
 *
 * Connecting, Saving and RollingBack ignore Cancel and Dismiss: the outcome of an accepted write must be known
 * before closing.
 * A failed rollback still closes the wizard; the handler logs it and the binding stays visible in settings.
 */
public val ConnectWizardMachineSpec:
    MachineSpec<ConnectWizardState, ConnectWizardIntent, ConnectWizardEffect, ConnectWizardOutput> =
    machineSpec(ConnectWizardMachineKey, Idle) {
        state<Idle> {
            on<Public.Start> {
                goto<ChoosingEngine> { ChoosingEngine(preselected = intent.engine) }
                effect { ObserveEngines }
            }
            on<Public.Cancel> { cancel() }
            on<Public.Dismiss> { cancel() }
        }
        state<ChoosingEngine> {
            on<Internal.EnginesChanged>(guard = { state.preselectedIn(intent.engines) != null }) {
                goto<ChoosingMethod> { ChoosingMethod(requireNotNull(state.preselectedIn(intent.engines))) }
            }
            on<Internal.EnginesChanged>(guard = { state.preselectedIn(intent.engines) == null }) {
                stay {
                    // An empty catalog is still loading; the preselection waits for the first real one.
                    val preselected = state.preselected.takeIf { intent.engines.isEmpty() }
                    state.copy(engines = intent.engines, preselected = preselected, failure = null)
                }
            }
            on<Internal.EnginesFailed> { stay { state.copy(failure = intent.failure) } }
            on<Public.Retry>(guard = { state.failure != null }) {
                goto<ChoosingEngine> { state.copy(failure = null) }
                effect { ObserveEngines }
            }
            on<Public.ChooseEngine>(guard = { state.engines.connectable(intent.engine) != null }) {
                goto<ChoosingMethod> { ChoosingMethod(requireNotNull(state.engines.connectable(intent.engine))) }
            }
            on<Public.Cancel> { cancel() }
            on<Public.Dismiss> { cancel() }
        }
        state<ChoosingMethod> {
            on<Public.CheckConnection>(guard = { state.isReadyFor(intent.method, intent.credential) }) {
                stay { state.copy(failure = null, check = ConnectionCheck.Running) }
                effect {
                    val method = requireNotNull(state.engine.method(intent.method))
                    ConnectWizardEffect.CheckConnection(state.engine.descriptor.id, method, intent.credential)
                }
            }
            on<Internal.ConnectionChecked>(guard = { state.check == ConnectionCheck.Running }) {
                stay { state.copy(check = intent.result) }
            }
            on<Public.Connect>(guard = { state.isReadyFor(intent.method, intent.credential) }) {
                goto<Connecting> { Connecting(state.engine, intent.method) }
                effect {
                    val method = requireNotNull(state.engine.method(intent.method))
                    ConnectWizardEffect.Connect(state.engine.descriptor.id, method, intent.credential)
                }
            }
            on<Public.Back> {
                goto<ChoosingEngine> { ChoosingEngine() }
                effect { ObserveEngines }
            }
            on<Public.Dismiss> {
                goto<ChoosingEngine> { ChoosingEngine() }
                effect { ObserveEngines }
            }
            on<Public.Cancel> { cancel() }
        }
        state<Connecting> {
            on<Internal.Connected> {
                goto<ChoosingModels> { ChoosingModels(state.engine.descriptor.id, intent.connection) }
                effect { ConnectWizardEffect.DiscoverModels(state.engine.descriptor.id, intent.connection.binding) }
            }
            on<Internal.ConnectFailed> { goto<ChoosingMethod> { ChoosingMethod(state.engine, intent.failure) } }
        }
        state<ChoosingModels> {
            on<Internal.ModelsLoaded> {
                stay {
                    val ids = intent.models.map { it.target.model }.toSet()
                    state.copy(models = intent.models, selected = state.selected intersect ids, failure = null)
                }
            }
            on<Internal.ModelsFailed> { stay { state.copy(failure = intent.failure) } }
            on<Public.Retry>(guard = { state.failure != null && state.models == null }) {
                stay { state.copy(failure = null) }
                effect { ConnectWizardEffect.DiscoverModels(state.engine, state.connection.binding) }
            }
            on<Public.Retry>(guard = { state.failure != null && state.models != null }) {
                goto<Saving> { Saving(state.engine, state.connection, state.models, state.selected) }
                effect { ConnectWizardEffect.SaveModels(state.connection.binding, state.selected) }
            }
            on<Public.ToggleModel>(guard = { state.models.orEmpty().any { it.target.model == intent.model } }) {
                stay {
                    val selected = state.selected
                    state.copy(
                        selected = if (intent.model in selected) selected - intent.model else selected + intent.model,
                    )
                }
            }
            on<Public.SelectAllModels>(guard = { state.models != null }) {
                stay {
                    val all = state.models.orEmpty().map { it.target.model }.toSet()
                    state.copy(selected = if (intent.isSelected) all else emptySet())
                }
            }
            on<Public.Finish>(guard = { state.models != null || state.failure != null }) {
                goto<Saving> { Saving(state.engine, state.connection, state.models, state.selected) }
                effect { ConnectWizardEffect.SaveModels(state.connection.binding, state.selected) }
            }
            on<Public.Cancel> { rollBack() }
            on<Public.Dismiss> { rollBack() }
        }
        state<Saving> {
            on<Internal.Saved> {
                goto<Finished> { Finished(state.connection.binding) }
                output { ConnectWizardOutput.Completed(state.connection.binding) }
            }
            on<Internal.SaveFailed> {
                goto<ChoosingModels> {
                    ChoosingModels(state.engine, state.connection, state.models, state.selected, intent.failure)
                }
            }
        }
        state<RollingBack> {
            on<Internal.RolledBack> {
                goto<Cancelled> { Cancelled }
                output { ConnectWizardOutput.Cancelled }
            }
        }
        state<Finished>()
        state<Cancelled>()
        onEffectFailure { effect, error ->
            val failure = error.toEngineFailure()
            when (effect) {
                ObserveEngines -> Internal.EnginesFailed(failure)
                is ConnectWizardEffect.Connect -> Internal.ConnectFailed(failure)
                is ConnectWizardEffect.CheckConnection -> Internal.ConnectionChecked(ConnectionCheck.Failed(failure))
                is ConnectWizardEffect.DiscoverModels -> Internal.ModelsFailed(failure)
                is ConnectWizardEffect.SaveModels -> Internal.SaveFailed(failure)
                is ConnectWizardEffect.Rollback -> Internal.RolledBack
            }
        }
    }

private fun <T : ConnectWizardState, J : ConnectWizardIntent> WizardTransition<T, J>.cancel() {
    goto<Cancelled> { Cancelled }
    output { ConnectWizardOutput.Cancelled }
}

private fun <J : ConnectWizardIntent> WizardTransition<ChoosingModels, J>.rollBack() {
    goto<RollingBack> { RollingBack(state.connection) }
    effect { ConnectWizardEffect.Rollback(state.connection) }
}

private typealias WizardTransition<T, J> =
    TransitionBuilder<ConnectWizardState, T, ConnectWizardIntent, J, ConnectWizardEffect, ConnectWizardOutput>

private fun ChoosingEngine.preselectedIn(engines: List<EngineInfo>): EngineInfo? =
    preselected?.let { engines.connectable(it) }

private fun List<EngineInfo>?.connectable(engine: EngineId): EngineInfo? =
    orEmpty().firstOrNull { it.descriptor.id == engine && it.isConnectable }

private fun EngineInfo.method(id: ConnectionMethodId): ConnectionMethod? =
    descriptor.connectionMethods.firstOrNull { it.id == id }

private fun ChoosingMethod.isReadyFor(method: ConnectionMethodId, credential: CredentialInput): Boolean =
    check != ConnectionCheck.Running && engine.method(method)?.accepts(credential) == true
