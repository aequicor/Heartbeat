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
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.ChoosingRuntime
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Connecting
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Finished
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Idle
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.PreparingRuntime
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.RollingBack
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Saving
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference

/**
 * Wizard engine → authentication → models. The binding is created before model discovery, because models are only
 * resolvable through an exact binding; abandoning the wizard afterwards rolls a newly created connection back and
 * conditionally restores the wizard's runtime choice without overwriting later settings.
 * An existing CLI binding is reused with its saved model choice. A disabled route is enabled for the wizard;
 * checks, cancellation and rejected delivery restore its original enabled state. Finishing keeps it enabled.
 * Previously saved models absent from discovery are preserved unless the user explicitly deselects them when shown.
 * Bulk selection applies only to discovered models; it cannot silently erase an unavailable saved choice.
 *
 * | From | Intent | Guard | To | Effect / Output |
 * |---|---|---|---|---|
 * | Idle | Start | | ChoosingEngine | ObserveEngines |
 * | ChoosingEngine | EnginesChanged | preselected engine connectable | PreparingRuntime | LoadRuntimeOptions |
 * | ChoosingEngine | EnginesChanged | otherwise | stay (preselection dropped unless the catalog is empty) | |
 * | ChoosingEngine | EnginesFailed | | stay (failure) | |
 * | ChoosingEngine | Retry | has failure | ChoosingEngine (re-entry) | ObserveEngines |
 * | ChoosingEngine | ChooseEngine | engine connectable | PreparingRuntime | LoadRuntimeOptions |
 * | PreparingRuntime | RuntimeOptionsLoaded | built in or management unavailable | ChoosingMethod | |
 * | PreparingRuntime | RuntimeOptionsLoaded | installation actions declared | ChoosingRuntime | |
 * | PreparingRuntime | RuntimeOptionsFailed / Retry | | stay / PreparingRuntime | LoadRuntimeOptions on retry |
 * | ChoosingRuntime | CheckLocalRuntime / Retry | idle | stay (checking) | CheckLocalRuntime |
 * | ChoosingRuntime | PickRuntimeFile / InspectRuntimeSource | checked, declared, idle | stay | inspect |
 * | ChoosingRuntime | InstallManagedRuntime | local check complete, declared and idle | stay (busy) | install |
 * | ChoosingRuntime | RuntimeFilePicked / RuntimeFilePickFailed | picking | stay | |
 * | ChoosingRuntime | UseRuntimeChoice | inspected baseline allowed | ChoosingMethod | |
 * | PreparingRuntime, ChoosingRuntime | Back / Dismiss | | ChoosingEngine | ObserveEngines |
 * | ChoosingMethod | PickRuntimeFile | no operation running | stay (picking) | PickRuntimeFile |
 * | ChoosingMethod | RuntimeFilePicked / RuntimeFilePickFailed | picking | stay | |
 * | ChoosingMethod | ClearRuntimeChoice | not picking | stay | |
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
 * | Saving | Checking | same operation | stay (phase Checking) | |
 * | Saving | Saved | same operation | Finished (save may be pending) | Completed |
 * | Saving | SaveFailed | same operation | ChoosingModels (failure, sequence retained) | |
 * | RollingBack | RolledBack | | Cancelled | Cancelled |
 * | Idle, ChoosingEngine, ChoosingMethod | Cancel | | Cancelled | Cancelled |
 * | Idle, ChoosingEngine | Dismiss | | Cancelled | Cancelled |
 *
 * Connecting, Saving and RollingBack ignore Cancel and Dismiss. Saving ends its bounded UI wait even if an accepted
 * write is still pending; Finished/Completed keep that fact explicit. The profile reconciles a late save without
 * starting late probes. Checking is a nested phase: leaving Saving would cancel the waiting effect prematurely.
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
                goto<PreparingRuntime> { PreparingRuntime(requireNotNull(state.preselectedIn(intent.engines))) }
                effect {
                    val selected = requireNotNull(state.preselectedIn(intent.engines))
                    ConnectWizardEffect.LoadRuntimeOptions(selected.descriptor.id)
                }
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
                goto<PreparingRuntime> { PreparingRuntime(requireNotNull(state.engines.connectable(intent.engine))) }
                effect { ConnectWizardEffect.LoadRuntimeOptions(intent.engine) }
            }
            on<Public.Cancel> { cancel() }
            on<Public.Dismiss> { cancel() }
        }
        state<PreparingRuntime> {
            on<Internal.RuntimeOptionsLoaded>(guard = { intent.options == null }) {
                goto<ChoosingMethod> { ChoosingMethod(state.engine) }
            }
            on<Internal.RuntimeOptionsLoaded>(guard = { intent.options != null }) {
                goto<ChoosingRuntime> { ChoosingRuntime(state.engine, requireNotNull(intent.options)) }
                effect { ConnectWizardEffect.ObserveRuntimeJob(state.engine.descriptor.id) }
            }
            on<Internal.RuntimeOptionsFailed> { stay { state.copy(failure = intent.failure) } }
            on<Public.Retry>(guard = { state.failure != null }) {
                goto<PreparingRuntime> { state.copy(failure = null) }
                effect { ConnectWizardEffect.LoadRuntimeOptions(state.engine.descriptor.id) }
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
        state<ChoosingRuntime> {
            on<Internal.RuntimeJobChanged> {
                stay {
                    state.copy(
                        runtimeJob = intent.job,
                        options = state.options.copy(isManagedInstallAvailable = intent.isManagedInstallAvailable),
                    )
                }
            }
            on<Public.CheckLocalRuntime>(guard = { !state.isPicking && !state.isManagementBusy() }) {
                stay { state.copy(isPicking = true, isCheckingLocal = true, failure = null) }
                effect {
                    val source = listOf(RuntimePreference.System, RuntimePreference.Bundled)
                        .firstOrNull { it in state.options.sources }
                    ConnectWizardEffect.CheckLocalRuntime(state.engine.descriptor.id, source)
                }
            }
            on<Public.Retry>(guard = { !state.isPicking && !state.isManagementBusy() && state.failure != null }) {
                stay { state.copy(isPicking = true, isCheckingLocal = true, failure = null) }
                effect {
                    val source = listOf(RuntimePreference.System, RuntimePreference.Bundled)
                        .firstOrNull { it in state.options.sources }
                    ConnectWizardEffect.CheckLocalRuntime(state.engine.descriptor.id, source)
                }
            }
            on<Public.InstallManagedRuntime>(
                guard = {
                    state.hasCheckedLocal && !state.isPicking && !state.isManagementBusy() &&
                        state.options.isManagedInstallAvailable
                },
            ) {
                stay { state.copy(isPicking = true, isInstalling = true, failure = null) }
                effect { ConnectWizardEffect.InstallManagedRuntime(state.engine.descriptor.id) }
            }
            on<Internal.RuntimeInstallationStarted>(guard = { state.isInstalling && state.isPicking }) {
                stay { state.copy(isInstallationStarted = true, installationHandle = intent.handle) }
            }
            on<Public.CancelRuntimeInstallation>(guard = {
                !state.isCancellingInstallation && state.cancellableInstallation() === intent.handle
            }) {
                stay { state.copy(isCancellingInstallation = true) }
                effect { ConnectWizardEffect.CancelRuntimeInstallation(intent.handle) }
            }
            on<Internal.RuntimeInstallationCancelled>(guard = {
                state.isInstalling || state.isCancellingInstallation
            }) {
                stay {
                    state.copy(
                        isPicking = false,
                        isInstalling = false,
                        isInstallationStarted = false,
                        installationHandle = null,
                        isCancellingInstallation = false,
                    )
                }
            }
            on<Internal.RuntimeInstallationCancelFailed>(guard = { state.isCancellingInstallation }) {
                stay { state.copy(isCancellingInstallation = false, failure = intent.failure) }
            }
            on<Internal.RuntimeInstallationAlreadyFinished>(guard = { state.isCancellingInstallation }) {
                stay { state.copy(isCancellingInstallation = false) }
            }
            on<Public.InspectRuntimeSource>(guard = {
                state.hasCheckedLocal && !state.isPicking && !state.isManagementBusy() &&
                    intent.source in state.options.sources
            }) {
                stay { state.copy(isPicking = true, failure = null) }
                effect { ConnectWizardEffect.InspectRuntimeSource(state.engine.descriptor.id, intent.source) }
            }
            on<Public.PickRuntimeFile>(guard = {
                state.hasCheckedLocal && state.options.isFilePickerAvailable && !state.isPicking &&
                    !state.isManagementBusy()
            }) {
                stay { state.copy(isPicking = true, failure = null) }
                effect { ConnectWizardEffect.PickRuntimeFile(state.engine.descriptor.id) }
            }
            on<Internal.RuntimeFilePicked>(guard = { state.isPicking }) {
                stay {
                    state.copy(
                        isPicking = false,
                        choice = intent.choice ?: state.choice,
                        hasCheckedLocal = state.hasCheckedLocal || state.isCheckingLocal,
                        isCheckingLocal = false,
                        isInstalling = false,
                        isInstallationStarted = false,
                        installationHandle = null,
                        isCancellingInstallation = false,
                    )
                }
            }
            on<Internal.RuntimeFilePickFailed>(guard = { state.isPicking }) {
                stay {
                    state.copy(
                        isPicking = false,
                        failure = intent.failure,
                        hasCheckedLocal = state.hasCheckedLocal || state.isCheckingLocal,
                        isCheckingLocal = false,
                        isInstalling = false,
                        isInstallationStarted = false,
                        installationHandle = null,
                        isCancellingInstallation = false,
                    )
                }
            }
            on<Public.ClearRuntimeChoice>(guard = { !state.isPicking }) {
                stay { state.copy(choice = null, failure = null) }
            }
            on<Public.UseRuntimeChoice>(
                guard = {
                    state.hasCheckedLocal && !state.isPicking && !state.isManagementBusy() &&
                        state.choice != null && state.choice.isUsable()
                },
            ) {
                goto<ChoosingMethod> {
                    ChoosingMethod(state.engine, runtimeChoice = state.choice, runtimeOptions = state.options)
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
        state<ChoosingMethod> {
            on<Public.PickRuntimeFile>(guard = { !state.isPickingRuntime && state.check != ConnectionCheck.Running }) {
                stay { state.copy(isPickingRuntime = true, failure = null) }
                effect { ConnectWizardEffect.PickRuntimeFile(state.engine.descriptor.id) }
            }
            on<Internal.RuntimeFilePicked>(guard = { state.isPickingRuntime }) {
                stay { state.copy(isPickingRuntime = false, runtimeChoice = intent.choice ?: state.runtimeChoice) }
            }
            on<Internal.RuntimeFilePickFailed>(guard = { state.isPickingRuntime }) {
                stay { state.copy(isPickingRuntime = false, failure = intent.failure) }
            }
            on<Public.ClearRuntimeChoice>(guard = { !state.isPickingRuntime }) {
                stay { state.copy(runtimeChoice = null, failure = null) }
            }
            on<Public.CheckConnection>(guard = {
                state.runtimeChoice == null && state.isReadyFor(intent.method, intent.credential)
            }) {
                stay { state.copy(failure = null, check = ConnectionCheck.Running) }
                effect {
                    val method = requireNotNull(state.engine.method(intent.method))
                    ConnectWizardEffect.CheckConnection(state.engine.descriptor.id, method, intent.credential)
                }
            }
            on<Internal.ConnectionChecked>(
                guard = { state.check == ConnectionCheck.Running && intent.result != ConnectionCheck.Running },
            ) {
                stay { state.copy(check = intent.result) }
            }
            on<Public.Connect>(guard = { state.isReadyFor(intent.method, intent.credential) }) {
                goto<Connecting> { Connecting(state.engine, intent.method, state.runtimeChoice, state.runtimeOptions) }
                effect {
                    val method = requireNotNull(state.engine.method(intent.method))
                    ConnectWizardEffect.Connect(
                        state.engine.descriptor.id,
                        method,
                        intent.credential,
                        state.runtimeChoice,
                    )
                }
            }
            on<Public.Back>(guard = { state.runtimeOptions == null }) {
                goto<ChoosingEngine> { ChoosingEngine() }
                effect { ObserveEngines }
            }
            on<Public.Back>(guard = { state.runtimeOptions != null }) {
                goto<ChoosingRuntime> {
                    ChoosingRuntime(
                        state.engine,
                        requireNotNull(state.runtimeOptions),
                        state.runtimeChoice,
                        hasCheckedLocal = true,
                    )
                }
                effect { ConnectWizardEffect.ObserveRuntimeJob(state.engine.descriptor.id) }
            }
            on<Public.Dismiss>(guard = { state.runtimeOptions == null }) {
                goto<ChoosingEngine> { ChoosingEngine() }
                effect { ObserveEngines }
            }
            on<Public.Dismiss>(guard = { state.runtimeOptions != null }) {
                goto<ChoosingRuntime> {
                    ChoosingRuntime(
                        state.engine,
                        requireNotNull(state.runtimeOptions),
                        state.runtimeChoice,
                        hasCheckedLocal = true,
                    )
                }
                effect { ConnectWizardEffect.ObserveRuntimeJob(state.engine.descriptor.id) }
            }
            on<Public.Cancel> { cancel() }
        }
        state<Connecting> {
            on<Internal.Connected> {
                goto<ChoosingModels> {
                    ChoosingModels(
                        state.engine.descriptor.id,
                        intent.connection,
                        selected = intent.connection.initialModels,
                    )
                }
                effect { ConnectWizardEffect.DiscoverModels(state.engine.descriptor.id, intent.connection.binding) }
            }
            on<Internal.ConnectFailed> {
                goto<ChoosingMethod> {
                    ChoosingMethod(
                        state.engine,
                        intent.failure,
                        runtimeChoice = state.runtimeChoice,
                        runtimeOptions = state.runtimeOptions,
                    )
                }
            }
        }
        state<ChoosingModels> {
            on<Internal.ModelsLoaded> {
                stay {
                    val ids = intent.models.map { it.target.model }.toSet()
                    val keep = ids + state.connection.initialModels
                    state.copy(models = intent.models, selected = state.selected intersect keep, failure = null)
                }
            }
            on<Internal.ModelsFailed> { stay { state.copy(failure = intent.failure) } }
            on<Public.Retry>(guard = { state.failure != null && state.models == null }) {
                stay { state.copy(failure = null) }
                effect { ConnectWizardEffect.DiscoverModels(state.engine, state.connection.binding) }
            }
            on<Public.Retry>(guard = { state.failure != null && state.models != null }) {
                goto<Saving> { state.saving() }
                effect { state.saveModels() }
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
                    state.copy(selected = if (intent.isSelected) state.selected + all else state.selected - all)
                }
            }
            on<Public.Finish>(guard = { state.models != null || state.failure != null }) {
                goto<Saving> { state.saving() }
                effect { state.saveModels() }
            }
            on<Public.Cancel> { rollBack() }
            on<Public.Dismiss> { rollBack() }
        }
        state<Saving> {
            on<Internal.Checking>(guard = { intent.operation == state.operation }) {
                stay { state.copy(phase = ModelSavePhase.Checking) }
            }
            on<Internal.Saved>(guard = { intent.operation == state.operation }) {
                goto<Finished> { Finished(state.connection.binding, intent.isSavePending) }
                output { ConnectWizardOutput.Completed(state.connection.binding, intent.isSavePending) }
            }
            on<Internal.SaveFailed>(guard = { intent.operation == state.operation }) {
                goto<ChoosingModels> {
                    ChoosingModels(
                        state.engine,
                        state.connection,
                        state.models,
                        state.selected,
                        intent.failure,
                        operationSequence = state.operation.value,
                    )
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
                is ConnectWizardEffect.LoadRuntimeOptions -> Internal.RuntimeOptionsFailed(failure)
                is ConnectWizardEffect.ObserveRuntimeJob -> Internal.RuntimeOptionsFailed(failure)
                is ConnectWizardEffect.CheckLocalRuntime -> Internal.RuntimeFilePickFailed(failure)
                is ConnectWizardEffect.PickRuntimeFile -> Internal.RuntimeFilePickFailed(failure)
                is ConnectWizardEffect.InspectRuntimeSource -> Internal.RuntimeFilePickFailed(failure)
                is ConnectWizardEffect.InstallManagedRuntime -> Internal.RuntimeFilePickFailed(failure)
                is ConnectWizardEffect.CancelRuntimeInstallation -> Internal.RuntimeInstallationCancelFailed(failure)
                is ConnectWizardEffect.Connect -> Internal.ConnectFailed(failure)
                is ConnectWizardEffect.CheckConnection -> Internal.ConnectionChecked(ConnectionCheck.Failed(failure))
                is ConnectWizardEffect.DiscoverModels -> Internal.ModelsFailed(failure)
                is ConnectWizardEffect.SaveModels -> Internal.SaveFailed(failure, effect.operation)
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

private fun ChoosingModels.saving(): Saving = Saving(
    engine,
    connection,
    models,
    selected,
    operation = ConnectionOperationId(operationSequence + 1),
)

private fun ChoosingModels.saveModels(): ConnectWizardEffect.SaveModels = ConnectWizardEffect.SaveModels(
    connection.binding,
    selected,
    operation = ConnectionOperationId(operationSequence + 1),
    previous = connection.initialModels,
    checkTargets = (selected - connection.initialModels).map { EngineTarget(engine, connection.binding, it) }.toSet(),
    removedTargets = (connection.initialModels - selected).map { EngineTarget(engine, connection.binding, it) }.toSet(),
)

private fun List<EngineInfo>?.connectable(engine: EngineId): EngineInfo? =
    orEmpty().firstOrNull { it.descriptor.id == engine && it.isConnectable }

private fun EngineInfo.method(id: ConnectionMethodId): ConnectionMethod? =
    descriptor.connectionMethods.firstOrNull { it.id == id }

private fun ChoosingMethod.isReadyFor(method: ConnectionMethodId, credential: CredentialInput): Boolean =
    !isPickingRuntime && runtimeChoice.isUsable() && check != ConnectionCheck.Running &&
        engine.method(method)?.accepts(credential) == true

private fun WizardRuntimeChoice?.isUsable(): Boolean {
    if (this == null) return true
    val found = inspection ?: return false
    return found.installation.isRunnable && found.installation.source != InstallSource.Missing &&
        found.compatibility?.decision?.isBaselineAllowed == true
}

private fun ChoosingRuntime.isManagementBusy(): Boolean = runtimeJob?.phase?.isFinished == false

private fun ChoosingRuntime.cancellableInstallation() = if (isInstalling && isInstallationStarted) {
    installationHandle
} else {
    runtimeJob?.takeIf {
        !it.phase.isFinished && (it.action == EngineAction.Install || it.action == EngineAction.Update)
    }
        ?.handle
}
