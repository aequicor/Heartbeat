package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo

/** Credential typed on the method step. [label] names the connection for the user and may contain PII. */
public sealed interface CredentialInput {
    /** User-visible connection name. */
    public val label: String

    /** Origin chosen for the method; equal to the method origin unless it is editable. */
    public val origin: EndpointOrigin

    /** A key for [ConnectionMethod.ApiKey]. The effect handling the connection closes [key] after use. */
    public data class ApiKey(override val label: String, override val origin: EndpointOrigin, public val key: Secret) :
        CredentialInput {
        override fun toString(): String = "CredentialInput.ApiKey(origin=$origin)"
    }

    /** No typed secret: the engine's own CLI login or an endpoint without authentication. */
    public data class Existing(override val label: String, override val origin: EndpointOrigin) : CredentialInput {
        override fun toString(): String = "CredentialInput.Existing(origin=$origin)"
    }
}

/** Whether [credential] fits this method: kind, origin policy and a non-blank label. */
public fun ConnectionMethod.accepts(credential: CredentialInput): Boolean = credential.label.isNotBlank() &&
    (isOriginEditable || credential.origin == origin) &&
    (this is ConnectionMethod.ApiKey) == (credential is CredentialInput.ApiKey)

/** Binding created by the wizard together with the source it owns until the wizard completes. */
public data class NewConnection(val binding: EngineBindingId, val source: AuthSourceId)

/** Steps of the wizard. The wizard never keeps typed keys in its state. */
public sealed interface ConnectWizardState : MachineState {
    /** Not started. */
    public data object Idle : ConnectWizardState

    /**
     * Step 1. [engines] is null until the catalog is observed; [preselected] skips the step if it is connectable
     * in the first non-empty catalog and is dropped afterwards, so a later catalog never pulls the user away.
     */
    public data class ChoosingEngine(
        val engines: List<EngineInfo>? = null,
        val preselected: EngineId? = null,
        val failure: EngineFailure? = null,
    ) : ConnectWizardState

    /** Step 2: provider and authentication method of [engine]; [failure] explains a rejected connection attempt. */
    public data class ChoosingMethod(val engine: EngineInfo, val failure: EngineFailure? = null) : ConnectWizardState

    /** Creating the source and the binding. */
    public data class Connecting(val engine: EngineInfo, val method: ConnectionMethodId) : ConnectWizardState

    /**
     * Step 3: models discovered through the new connection; [models] is null while discovering.
     * [failure] belongs to discovery while [models] is null and to saving once they are loaded.
     */
    public data class ChoosingModels(
        val engine: EngineId,
        val connection: NewConnection,
        val models: List<ModelInfo>? = null,
        val selected: Set<ModelId> = emptySet(),
        val failure: EngineFailure? = null,
    ) : ConnectWizardState

    /** Persisting the model choice. */
    public data class Saving(
        val engine: EngineId,
        val connection: NewConnection,
        val models: List<ModelInfo>?,
        val selected: Set<ModelId>,
    ) : ConnectWizardState

    /** Removing the connection created by an abandoned wizard. */
    public data class RollingBack(val connection: NewConnection) : ConnectWizardState

    /** The connection is saved. */
    public data class Finished(val binding: EngineBindingId) : ConnectWizardState

    /** Closed without a connection. */
    public data object Cancelled : ConnectWizardState
}

/** User steps and effect results of the wizard. */
public sealed interface ConnectWizardIntent : MachineIntent {
    /** Accepted from the wizard screen and other features. */
    public sealed interface Public : ConnectWizardIntent {
        /** Starts the wizard, optionally for one engine. */
        public data class Start(val engine: EngineId? = null) : Public

        /** Picks a connectable engine. */
        public data class ChooseEngine(val engine: EngineId) : Public

        /** Creates the connection. An ignored request leaves the key with the sender, who closes it. */
        public data class Connect(val method: ConnectionMethodId, val credential: CredentialInput) : Public

        /** Returns from the method step to the engine step. */
        public data object Back : Public

        /** Enables or disables one discovered model. */
        public data class ToggleModel(val model: ModelId) : Public

        /** Selects every discovered model, or none. */
        public data class SelectAllModels(val isSelected: Boolean) : Public

        /** Repeats the failed step: catalog observation, model discovery or saving the model choice. */
        public data object Retry : Public

        /** Saves the model choice and completes the wizard. */
        public data object Finish : Public

        /** Abandons the wizard, removing a connection it has already created. */
        public data object Cancel : Public

        /**
         * System back or closing the screen: steps back from the method step, otherwise acts as [Cancel].
         * Ignored while a write is running, so its outcome is known before the wizard closes.
         */
        public data object Dismiss : Public
    }

    /** Effect results. */
    public sealed interface Internal : ConnectWizardIntent {
        /** Fresh engine catalog. */
        public data class EnginesChanged(val engines: List<EngineInfo>) : Internal

        /** The catalog could not be observed. */
        public data class EnginesFailed(val failure: EngineFailure) : Internal

        /** Source and binding exist. */
        public data class Connected(val connection: NewConnection) : Internal

        /** Nothing was created. */
        public data class ConnectFailed(val failure: EngineFailure) : Internal

        /** Models reachable through the connection. */
        public data class ModelsLoaded(val models: List<ModelInfo>) : Internal

        /** Discovery failed; the connection is kept. */
        public data class ModelsFailed(val failure: EngineFailure) : Internal

        /** The model choice is stored. */
        public data object Saved : Internal

        /** The model choice could not be stored. */
        public data class SaveFailed(val failure: EngineFailure) : Internal

        /** The abandoned connection is removed (or its removal failed and was logged). */
        public data object RolledBack : Internal
    }
}

/** IO requested by the wizard. */
public sealed interface ConnectWizardEffect : MachineEffect {
    /** Observes the engine catalog while choosing an engine. */
    public data object ObserveEngines : ConnectWizardEffect

    /** Probes installation, creates the source and binds it; removes the source again if binding fails. */
    public data class Connect(val engine: EngineId, val method: ConnectionMethod, val credential: CredentialInput) :
        ConnectWizardEffect

    /** Explicit model discovery through the new binding. */
    public data class DiscoverModels(val engine: EngineId, val binding: EngineBindingId) : ConnectWizardEffect

    /** Stores the enabled models of the new binding. */
    public data class SaveModels(val binding: EngineBindingId, val models: Set<ModelId>) : ConnectWizardEffect

    /** Disconnects the binding and forgets its source. */
    public data class Rollback(val connection: NewConnection) : ConnectWizardEffect
}

/** One-off wizard results. */
public sealed interface ConnectWizardOutput : MachineOutput {
    /** The connection is ready. */
    public data class Completed(val binding: EngineBindingId) : ConnectWizardOutput

    /** The wizard closed without a connection. */
    public data object Cancelled : ConnectWizardOutput
}

/**
 * Address of the connection wizard machine. One machine runs per open wizard route and ends with it; the screen
 * closes on [ConnectWizardState.Finished] or [ConnectWizardState.Cancelled]. Destroying the route without
 * [ConnectWizardIntent.Public.Dismiss] (process death, profile switch) cannot roll back: a connection created by then
 * stays and remains visible in the settings space.
 */
public object ConnectWizardMachineKey :
    MachineKey<
        ConnectWizardState,
        ConnectWizardIntent,
        ConnectWizardIntent.Public,
        ConnectWizardEffect,
        ConnectWizardOutput,
    > {
    override val name: String = "connect_wizard"
}
