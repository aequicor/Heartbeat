package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointBaseUrl
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.canonicalBaseUrl
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.canonicalOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.EngineConnectionsScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelDescriptions
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.isCompatibleOriginAllowed
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

/** User events of the settings space. Navigation is handled by the component, not the store. */
sealed interface EngineConnectionsScreenIntent : MVIIntent {
    /** A request executed by the machine as a [ConnectionOperation]. */
    sealed interface Change : EngineConnectionsScreenIntent

    /** Local view state only; these events never start IO. */
    sealed interface Local : EngineConnectionsScreenIntent

    /** Local actions in the connection editor. */
    sealed interface EditAction : Local

    /** Focuses an engine. */
    data class SelectEngine(val id: String) : Local

    /** Focuses a connection of the focused engine. */
    data class SelectConnection(val id: String) : Local

    /** Opens and edits a local credential draft; the existing secret is never loaded into it. */
    data object StartEdit : EditAction

    /** Changes the connection name. */
    data class EditLabel(val value: String) : EditAction

    /** Changes the proposed server address. */
    data class EditOrigin(val value: String) : EditAction

    /** Changes the new key without logging it. */
    data class EditKey(val value: String) : EditAction {
        override fun toString(): String = "EditKey(***)"
    }

    /** Discards the unsaved draft. */
    data object CancelEdit : EditAction

    /** Submits the draft once. */
    data object SaveEdit : EngineConnectionsScreenIntent

    /** Probes the focused engine's installation. */
    data object ProbeEngine : Change

    /** Enables or disables a connection. */
    data class SetConnectionEnabled(val id: String, val isEnabled: Boolean) : Change

    /** Asks to confirm disconnecting a connection. */
    data class RequestDisconnect(val id: String) : Local

    /** Confirms the pending disconnect. */
    data object ConfirmDisconnect : Change

    /** Keeps the connection. */
    data object DismissDisconnect : Local

    /** Explicit model discovery for the focused connection. */
    data object RefreshModels : Change

    /** Filters models. */
    data class SearchModels(val query: String) : Local

    /** Drafts offering or hiding a model of the focused connection. */
    data class SetModelEnabled(val id: String, val isEnabled: Boolean) : Local

    /** Drafts all cached models, or none. */
    data class SetAllModels(val isEnabled: Boolean) : Local

    /** Saves the drafted model choice of the focused connection. */
    data object SaveModels : Change

    /** Makes a model of the focused connection the profile default. */
    data class SetDefaultModel(val id: String) : Change

    /** Restarts a failed observation. */
    data object RetryLoad : EngineConnectionsScreenIntent

    /** Repeats a failed change. */
    data object RetryFailed : EngineConnectionsScreenIntent

    /** Hides the change failure. */
    data object DismissError : EngineConnectionsScreenIntent

    /** Opens current diagnostic data locally, without a request. */
    data class ShowCheckDetails(val target: ModelCheckTargetUi) : Local

    /** Closes details locally without changing model choices or invoking a check. */
    data object DismissCheckDetails : Local

    /** Explicit retry of the exact route in the current details. */
    data object RetryModelCheck : Change
}

/** The settings space has no one-off screen actions. */
sealed interface EngineConnectionsScreenAction : MVIAction

/** Settings store: reflects the snapshot and keeps focus, search, confirmation and the model draft locally. */
@SingleIn(EngineConnectionsScope::class)
@Inject
class EngineConnectionsModel(
    private val machine: Machine<EngineConnectionsState, EngineConnectionsIntent, EngineConnectionsOutput>,
    @ForScope(EngineConnectionsScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
    target: EngineConnectionsTarget = EngineConnectionsTarget(),
    library: ModelDescriptions,
) {
    private val log = Log.tag("EngineConnectionsModel")

    private val screen = factory.createScreen<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >(
        "EngineConnections",
        EngineConnectionsScreenState(
            fixedEngine = target.engine?.value,
            selectedEngine = target.engine?.value,
        ).reflect(machine.state.value),
        onError = {
            copy(
                isLoading = false,
                isSaving = false,
                isModelSavePending = false,
                failure = FailureUi.Unknown,
                checkDetails = null,
            )
        },
    ) {
        init { sendTo(machine, EngineConnectionsIntent.Public.Start) }
        reflect(machine) { reflect(it) }
        whileSubscribed {
            library.observe().collect { found ->
                updateState { copy(descriptions = found?.toImmutableSet()).reflect(machine.state.value) }
            }
        }
        reduce { intent -> handle(intent) }
    }
    val store = screen.store

    /** Latest screen state, read synchronously for the first frame. */
    val current = screen.current

    init {
        store.start(scope.coroutineScope)
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >.handle(
        intent: EngineConnectionsScreenIntent,
    ) {
        when (intent) {
            EngineConnectionsScreenIntent.StartEdit -> {
                sendTo(machine, EngineConnectionsIntent.Public.DismissError)
                updateState { localChange(EngineConnectionsScreenIntent.StartEdit, machine.state.value) }
            }

            is EngineConnectionsScreenIntent.Local -> updateState { localChange(intent, machine.state.value) }

            EngineConnectionsScreenIntent.RetryLoad -> sendTo(machine, EngineConnectionsIntent.Public.RetryLoad)

            EngineConnectionsScreenIntent.RetryFailed -> sendTo(machine, EngineConnectionsIntent.Public.RetryFailed)

            EngineConnectionsScreenIntent.DismissError -> sendTo(machine, EngineConnectionsIntent.Public.DismissError)

            EngineConnectionsScreenIntent.SaveEdit -> saveEdit()

            is EngineConnectionsScreenIntent.SetDefaultModel -> {
                updateState { editModel(intent.id, true) }
                apply(intent)
            }

            is EngineConnectionsScreenIntent.Change -> apply(intent)
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >.apply(
        intent: EngineConnectionsScreenIntent.Change,
    ) {
        var screen = EngineConnectionsScreenState()
        withState { screen = this }
        val operation = screen.operationFor(intent)
        if (operation == null) {
            log.w { "ignore ${intent::class.simpleName.orEmpty()} without a focused entry" }
            return
        }
        val result = sendTo(machine, EngineConnectionsIntent.Public.Apply(operation))
        // The confirmation closes only once the machine took the disconnect; a rejected one stays to be confirmed.
        if (intent == EngineConnectionsScreenIntent.ConfirmDisconnect && result == SendResult.Accepted) {
            updateState { copy(confirmDisconnect = null) }
        }
    }

    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >.saveEdit() {
        var screen = EngineConnectionsScreenState()
        withState { screen = this }
        val draft = screen.editDraft?.takeUnless { screen.isSaving || screen.isEditFailure || it.form.label.isBlank() }
            ?: return
        val active = machine.state.value as? EngineConnectionsState.Active ?: return
        val endpoint = draft.endpoint()
        val error = when {
            endpoint == null -> FormError.InvalidOrigin

            CompatibleProtocol.entries.any { it.provider.id == draft.method.provider.id } &&
                !isCompatibleOriginAllowed(endpoint.origin) -> FormError.InsecureOrigin

            else -> null
        }
        if (error != null) {
            updateState { copy(editDraft = editDraft?.copy(formError = error)) }
        } else {
            sendEdit(draft, active.operationSequence + 1, requireNotNull(endpoint))
        }
    }

    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        EngineConnectionsScreenState,
        EngineConnectionsScreenIntent,
        EngineConnectionsScreenAction,
    >.sendEdit(
        draft: ConnectionEditDraft,
        sequence: Long,
        endpoint: EndpointBaseUrl,
    ) {
        updateState {
            copy(
                editDraft = editDraft?.copy(
                    form = editDraft.form.copy(key = SecretText()),
                    submittedSequence = sequence,
                    formError = null,
                ),
            )
        }
        val key = draft.form.key.value.trim().takeIf { it.isNotEmpty() }?.let { Secret(it.toCharArray()) }
        val edit = ConnectionEdit(
            EngineBindingId(draft.binding),
            draft.method.id,
            draft.source,
            draft.form.label.trim(),
            endpoint.origin,
            endpoint.basePath,
            key,
        )
        var isAccepted = false
        try {
            withContext(NonCancellable) {
                isAccepted = sendTo(machine, EngineConnectionsIntent.Public.Edit(edit)) == SendResult.Accepted
            }
        } finally {
            if (!isAccepted) key?.close()
        }
        if (!isAccepted) updateState { copy(editDraft = editDraft?.copy(submittedSequence = null)) }
    }
}

private fun ConnectionEditDraft.endpoint(): EndpointBaseUrl? = when {
    method.isPathEditable -> canonicalBaseUrl(form.origin)
    method.isOriginEditable -> canonicalOrigin(form.origin)?.let(::EndpointBaseUrl)
    else -> EndpointBaseUrl(source.scope.origin, source.scope.basePath)
}

/** Translates a change request into a machine operation against the focused engine and connection. */
internal fun EngineConnectionsScreenState.operationFor(
    intent: EngineConnectionsScreenIntent.Change,
): ConnectionOperation? {
    val binding = selectedConnection?.let(::EngineBindingId)
    return when (intent) {
        EngineConnectionsScreenIntent.ProbeEngine -> selectedEngine?.let {
            ConnectionOperation.ProbeEngine(
                EngineId(it),
            )
        }

        is EngineConnectionsScreenIntent.SetConnectionEnabled ->
            connections.singleOrNull { it.id == intent.id }?.let {
                ConnectionOperation.SetConnectionEnabled(EngineBindingId(it.id), intent.isEnabled)
            }

        EngineConnectionsScreenIntent.ConfirmDisconnect ->
            confirmDisconnect?.takeIf { id -> connections.any { it.id == id } }
                ?.let { ConnectionOperation.Disconnect(EngineBindingId(it)) }

        EngineConnectionsScreenIntent.RefreshModels -> {
            val engine = selectedEngine?.let(::EngineId)
            if (engine != null && binding != null) ConnectionOperation.RefreshModels(engine, binding) else null
        }

        EngineConnectionsScreenIntent.SaveModels -> modelSaveOperation()

        is EngineConnectionsScreenIntent.SetDefaultModel -> target(intent.id)?.let(ConnectionOperation::SetDefaultModel)

        EngineConnectionsScreenIntent.RetryModelCheck -> checkDetails?.check?.target?.let {
            ConnectionOperation.CheckModel(
                EngineTarget(EngineId(it.engine), EngineBindingId(it.binding), ModelId(it.model)),
            )
        }
    }
}

private fun EngineConnectionsScreenState.modelSaveOperation(): ConnectionOperation.SetModelsEnabled? {
    val binding = selectedConnection?.let(::EngineBindingId) ?: return null
    val draft = modelDraft.takeIf { modelDraftBinding == binding.value } ?: return null
    val baseline = modelDraftBaseline ?: return null
    return ConnectionOperation.SetModelsEnabled(
        binding = binding,
        models = draft.map(::ModelId).toSet(),
        previous = baseline.map(::ModelId).toSet(),
    )
}

private fun EngineConnectionsScreenState.localChange(
    intent: EngineConnectionsScreenIntent.Local,
    current: EngineConnectionsState,
): EngineConnectionsScreenState = when (intent) {
    is EngineConnectionsScreenIntent.SelectEngine -> selectEngine(intent.id, current)

    is EngineConnectionsScreenIntent.SelectConnection -> {
        if (connections.none { it.id == intent.id }) {
            this
        } else {
            val selected = copy(selectedConnection = intent.id, confirmDisconnect = null, editDraft = null)
            (if (selectedConnection == intent.id) selected else selected.clearModelDraft()).reflect(current)
        }
    }

    is EngineConnectionsScreenIntent.EditAction -> editForm(intent, current)

    is EngineConnectionsScreenIntent.RequestDisconnect ->
        if (connections.any { it.id == intent.id }) copy(confirmDisconnect = intent.id) else this

    EngineConnectionsScreenIntent.DismissDisconnect -> copy(confirmDisconnect = null)

    is EngineConnectionsScreenIntent.SearchModels -> copy(modelQuery = intent.query)

    is EngineConnectionsScreenIntent.SetModelEnabled -> editModel(intent.id, intent.isEnabled)

    is EngineConnectionsScreenIntent.SetAllModels -> editAllModels(intent.isEnabled)

    is EngineConnectionsScreenIntent.ShowCheckDetails -> reflect(current).openCheckDetails(intent.target)

    EngineConnectionsScreenIntent.DismissCheckDetails -> copy(checkDetails = null)
}

private fun EngineConnectionsScreenState.editForm(
    intent: EngineConnectionsScreenIntent.EditAction,
    current: EngineConnectionsState,
): EngineConnectionsScreenState = when (intent) {
    EngineConnectionsScreenIntent.StartEdit -> startEdit(current)

    is EngineConnectionsScreenIntent.EditLabel -> copy(
        editDraft = editDraft?.copy(
            form = editDraft.form.copy(label = intent.value),
            formError = null,
        ),
    )

    is EngineConnectionsScreenIntent.EditOrigin -> copy(
        editDraft = editDraft?.copy(
            form = editDraft.form.copy(origin = intent.value),
            formError = null,
        ),
    )

    is EngineConnectionsScreenIntent.EditKey -> copy(
        editDraft = editDraft?.copy(
            form = editDraft.form.copy(key = SecretText(intent.value)),
            formError = null,
        ),
    )

    EngineConnectionsScreenIntent.CancelEdit -> if (isSaving) this else copy(editDraft = null)
}

private fun EngineConnectionsScreenState.startEdit(current: EngineConnectionsState): EngineConnectionsScreenState {
    if (isSaving) return this
    val snapshot = (current as? EngineConnectionsState.Active)?.snapshot ?: return this
    val engine = snapshot.engines.singleOrNull { it.descriptor.id.value == selectedEngine } ?: return this
    val binding = engine.bindings.singleOrNull { it.id.value == selectedConnection } ?: return this
    val source = snapshot.sources.singleOrNull { it.info.id == binding.authSource } ?: return this
    val method = engine.descriptor.connectionMethods.editMethod(source) ?: return this
    val form = CredentialForm(source.info.label, source.scope.origin.value + source.scope.basePath.orEmpty())
    return copy(
        editDraft = ConnectionEditDraft(binding.id.value, source, method, form),
        isEditOutcomePending = false,
        failure = null,
        isEditFailure = false,
    )
}

/** Immutable routing input supplied by the connections entry; not a new profile authority. */
data class EngineConnectionsTarget(val engine: EngineId? = null)

private fun EngineConnectionsScreenState.selectEngine(
    id: String,
    current: EngineConnectionsState,
): EngineConnectionsScreenState = if (fixedEngine != null && id != fixedEngine) {
    this
} else {
    copy(selectedEngine = id, selectedConnection = null, confirmDisconnect = null, editDraft = null)
        .clearModelDraft().reflect(current)
}
