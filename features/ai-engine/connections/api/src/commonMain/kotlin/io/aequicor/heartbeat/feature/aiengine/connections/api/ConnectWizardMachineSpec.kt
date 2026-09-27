package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
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
 * | ChoosingEngine | EnginesChanged | otherwise | stay | |
 * | ChoosingEngine | EnginesFailed | | stay (failure) | |
 * | ChoosingEngine | Retry | has failure | ChoosingEngine (re-entry) | ObserveEngines |
 * | ChoosingEngine | ChooseEngine | engine connectable | ChoosingMethod | |
 * | ChoosingMethod | Connect | method of the engine accepts credential | Connecting | Connect |
 * | ChoosingMethod | Back | | ChoosingEngine | ObserveEngines |
 * | Connecting | Connected | | ChoosingModels | DiscoverModels |
 * | Connecting | ConnectFailed | | ChoosingMethod (failure) | |
 * | ChoosingModels | ModelsLoaded / ModelsFailed | | stay | |
 * | ChoosingModels | ToggleModel / SelectAllModels | models discovered | stay | |
 * | ChoosingModels | Retry | has failure | stay (discovering) | DiscoverModels |
 * | ChoosingModels | Finish | not discovering | Saving | SaveModels |
 * | ChoosingModels | Cancel | | RollingBack | Rollback |
 * | Saving | Saved | | Finished | Completed |
 * | Saving | SaveFailed | | ChoosingModels (failure) | |
 * | RollingBack | RolledBack | | Cancelled | Cancelled |
 * | Idle, ChoosingEngine, ChoosingMethod | Cancel | | Cancelled | Cancelled |
 *
 * Connecting and Saving ignore Cancel: the outcome of an accepted write must be known before closing.
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
            on<Public.Cancel> {
                goto<Cancelled> { Cancelled }
                output { ConnectWizardOutput.Cancelled }
            }
        }
        state<ChoosingEngine> {
            on<Internal.EnginesChanged>(guard = { state.preselectedIn(intent.engines) != null }) {
                goto<ChoosingMethod> { ChoosingMethod(requireNotNull(state.preselectedIn(intent.engines))) }
            }
            on<Internal.EnginesChanged>(guard = { state.preselectedIn(intent.engines) == null }) {
                stay { state.copy(engines = intent.engines, failure = null) }
            }
            on<Internal.EnginesFailed> { stay { state.copy(failure = intent.failure) } }
            on<Public.Retry>(guard = { state.failure != null }) {
                goto<ChoosingEngine> { state.copy(failure = null) }
                effect { ObserveEngines }
            }
            on<Public.ChooseEngine>(guard = { state.engines.connectable(intent.engine) != null }) {
                goto<ChoosingMethod> { ChoosingMethod(requireNotNull(state.engines.connectable(intent.engine))) }
            }
            on<Public.Cancel> {
                goto<Cancelled> { Cancelled }
                output { ConnectWizardOutput.Cancelled }
            }
        }
        state<ChoosingMethod> {
            on<Public.Connect>(guard = { state.engine.method(intent.method)?.accepts(intent.credential) == true }) {
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
            on<Public.Cancel> {
                goto<Cancelled> { Cancelled }
                output { ConnectWizardOutput.Cancelled }
            }
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
            on<Public.Retry>(guard = { state.failure != null }) {
                stay { state.copy(models = null, failure = null) }
                effect { ConnectWizardEffect.DiscoverModels(state.engine, state.connection.binding) }
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
            on<Public.Cancel> {
                goto<RollingBack> { RollingBack(state.connection) }
                effect { ConnectWizardEffect.Rollback(state.connection) }
            }
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
                is ConnectWizardEffect.DiscoverModels -> Internal.ModelsFailed(failure)
                is ConnectWizardEffect.SaveModels -> Internal.SaveFailed(failure)
                is ConnectWizardEffect.Rollback -> Internal.RolledBack
            }
        }
    }

private fun ChoosingEngine.preselectedIn(engines: List<EngineInfo>): EngineInfo? =
    preselected?.let { engines.connectable(it) }

private fun List<EngineInfo>?.connectable(engine: EngineId): EngineInfo? =
    orEmpty().firstOrNull { it.descriptor.id == engine && it.isConnectable }

private fun EngineInfo.method(id: ConnectionMethodId): ConnectionMethod? =
    descriptor.connectionMethods.firstOrNull { it.id == id }
