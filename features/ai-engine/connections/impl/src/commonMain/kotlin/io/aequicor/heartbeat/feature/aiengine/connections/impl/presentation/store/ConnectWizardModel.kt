package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import androidx.compose.runtime.Immutable
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
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.canonicalOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.ConnectWizardScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce

/** Wizard steps as shown to the user. */
enum class WizardStep { Engine, Method, Models, Done }

/** Form validation problems found before the machine is asked to connect. */
enum class FormError { MissingKey, InvalidOrigin }

/** Typed key; never rendered back, never printed. */
@Immutable
data class SecretText(val value: String = "") {
    override fun toString(): String = "SecretText(***)"
}

/** Editable credential form of the selected method. */
@Immutable
data class CredentialForm(val label: String = "", val origin: String = "", val key: SecretText = SecretText())

/** Wizard screen derived from the machine plus the local form. */
@Immutable
data class ConnectWizardScreenState(
    val step: WizardStep = WizardStep.Engine,
    val engineTitle: String = "",
    val engines: ImmutableList<EngineRowUi>? = null,
    val methods: ImmutableList<MethodRowUi> = persistentListOf(),
    val methodQuery: String = "",
    val selectedMethod: String? = null,
    val form: CredentialForm = CredentialForm(),
    val formError: FormError? = null,
    val models: ImmutableList<ModelRowUi>? = null,
    val modelQuery: String = "",
    val isBusy: Boolean = true,
    val failure: FailureUi? = null,
) : MVIState

/** User events of the wizard. */
sealed interface ConnectWizardScreenIntent : MVIIntent {
    /** Picks an engine on the first step. */
    data class ChooseEngine(val id: String) : ConnectWizardScreenIntent

    /** Filters providers. */
    data class SearchMethods(val query: String) : ConnectWizardScreenIntent

    /** Picks a provider/method; resets the form to its defaults. */
    data class SelectMethod(val id: String) : ConnectWizardScreenIntent

    /** Edits the connection name. */
    data class EditLabel(val value: String) : ConnectWizardScreenIntent

    /** Edits the endpoint of an editable method. */
    data class EditOrigin(val value: String) : ConnectWizardScreenIntent

    /** Edits the API key. */
    data class EditKey(val value: String) : ConnectWizardScreenIntent

    /** Creates the connection from the form. */
    data object Connect : ConnectWizardScreenIntent

    /** Filters models. */
    data class SearchModels(val query: String) : ConnectWizardScreenIntent

    /** Enables or disables a model. */
    data class ToggleModel(val id: String) : ConnectWizardScreenIntent

    /** Selects all or no models. */
    data class SelectAllModels(val isSelected: Boolean) : ConnectWizardScreenIntent

    /** Repeats a failed load. */
    data object Retry : ConnectWizardScreenIntent

    /** Returns to the previous step. */
    data object Back : ConnectWizardScreenIntent

    /** Saves the model choice. */
    data object Finish : ConnectWizardScreenIntent

    /** Abandons the wizard. */
    data object Cancel : ConnectWizardScreenIntent

    /**
     * System back: one step back from the method step, otherwise cancel with rollback. Ignored by the machine
     * while a write is in flight, so back never abandons a connection whose outcome is unknown.
     */
    data object SystemBack : ConnectWizardScreenIntent
}

/** One-off navigation requests. */
sealed interface ConnectWizardScreenAction : MVIAction {
    /** The wizard is over; [binding] is set when a connection was created. */
    data class Close(val binding: String?) : ConnectWizardScreenAction
}

/** Wizard store: reflects the machine, owns the transient form, never keeps a typed key after sending it. */
@SingleIn(ConnectWizardScope::class)
@Inject
class ConnectWizardModel(
    private val machine: Machine<ConnectWizardState, ConnectWizardIntent, ConnectWizardOutput>,
    route: ConnectEngineRoute,
    @ForScope(ConnectWizardScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val log = Log.tag("ConnectWizardModel")
    private val sentKeys = mutableListOf<Secret>()

    val store = factory.create<ConnectWizardScreenState, ConnectWizardScreenIntent, ConnectWizardScreenAction>(
        "ConnectWizard",
        ConnectWizardScreenState().reflect(machine.state.value),
        onError = { copy(isBusy = false, failure = FailureUi.Unknown) },
    ) {
        reflect(machine, onOutput = { output ->
            when (output) {
                is ConnectWizardOutput.Completed -> action(ConnectWizardScreenAction.Close(output.binding.value))
                ConnectWizardOutput.Cancelled -> action(ConnectWizardScreenAction.Close(null))
            }
        }) { reflect(it) }
        reduce { intent ->
            when (intent) {
                is ConnectWizardScreenIntent.ChooseEngine ->
                    sendTo(machine, ConnectWizardIntent.Public.ChooseEngine(EngineId(intent.id)))

                is ConnectWizardScreenIntent.SearchMethods -> updateState { copy(methodQuery = intent.query) }

                is ConnectWizardScreenIntent.SelectMethod -> updateState { selectMethod(intent.id) }

                is ConnectWizardScreenIntent.EditLabel ->
                    updateState { copy(form = form.copy(label = intent.value), formError = null) }

                is ConnectWizardScreenIntent.EditOrigin ->
                    updateState { copy(form = form.copy(origin = intent.value), formError = null) }

                is ConnectWizardScreenIntent.EditKey ->
                    updateState { copy(form = form.copy(key = SecretText(intent.value)), formError = null) }

                ConnectWizardScreenIntent.Connect -> connect()

                is ConnectWizardScreenIntent.SearchModels -> updateState { copy(modelQuery = intent.query) }

                is ConnectWizardScreenIntent.ToggleModel ->
                    sendTo(machine, ConnectWizardIntent.Public.ToggleModel(ModelId(intent.id)))

                is ConnectWizardScreenIntent.SelectAllModels ->
                    sendTo(machine, ConnectWizardIntent.Public.SelectAllModels(intent.isSelected))

                ConnectWizardScreenIntent.Retry -> sendTo(machine, ConnectWizardIntent.Public.Retry)

                ConnectWizardScreenIntent.Back -> sendTo(machine, ConnectWizardIntent.Public.Back)

                ConnectWizardScreenIntent.Finish -> sendTo(machine, ConnectWizardIntent.Public.Finish)

                ConnectWizardScreenIntent.Cancel -> sendTo(machine, ConnectWizardIntent.Public.Cancel)

                ConnectWizardScreenIntent.SystemBack -> sendTo(
                    machine,
                    if (machine.state.value is ConnectWizardState.ChoosingMethod) {
                        ConnectWizardIntent.Public.Back
                    } else {
                        ConnectWizardIntent.Public.Cancel
                    },
                )
            }
        }
    }

    init {
        scope.onClose { sentKeys.forEach(Secret::close) }
        store.start(scope.coroutineScope)
        scope.coroutineScope.launch { machine.send(ConnectWizardIntent.Public.Start(route.engine)) }
    }

    /** Validates the form, hands the key to the machine as an owned Secret, and clears it from the screen. */
    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        ConnectWizardScreenState,
        ConnectWizardScreenIntent,
        ConnectWizardScreenAction,
    >.connect() {
        val choosing = machine.state.value as? ConnectWizardState.ChoosingMethod ?: return
        var screen = ConnectWizardScreenState()
        withState { screen = this }
        val method = choosing.engine.descriptor.connectionMethods.firstOrNull { it.id.value == screen.selectedMethod }
            ?: return
        val request = screen.form.toRequest(method)
        if (request is FormCheck.Invalid) {
            updateState { copy(formError = request.error) }
            return
        }
        val credential = (request as FormCheck.Valid).credential
        val key = (credential as? CredentialInput.ApiKey)?.key
        var isHandedOver = false
        val result = try {
            sendTo(machine, ConnectWizardIntent.Public.Connect(method.id, credential))
                .also { isHandedOver = it == SendResult.Accepted }
        } finally {
            if (!isHandedOver) key?.close()
        }
        if (!isHandedOver) {
            log.w { "connect request not accepted result=$result" }
        } else {
            // The effect closes the key; closing again when the wizard scope ends covers an effect that never ran.
            key?.let { sentKeys += it }
            updateState { copy(form = form.copy(key = SecretText())) }
        }
    }
}

/** Outcome of validating the form against its method. */
internal sealed interface FormCheck {
    /** Ready to send; an API key credential owns a fresh Secret. */
    data class Valid(val credential: CredentialInput) : FormCheck

    /** Must be corrected first. */
    data class Invalid(val error: FormError) : FormCheck
}

/** Validates the form; a blank name falls back to the provider title. */
internal fun CredentialForm.toRequest(method: ConnectionMethod): FormCheck {
    val origin = if (method.isOriginEditable) canonicalOrigin(origin) else method.origin
    val label = label.trim().ifEmpty { method.provider.title }
    return when {
        origin == null -> FormCheck.Invalid(FormError.InvalidOrigin)

        method is ConnectionMethod.ApiKey && key.value.isBlank() -> FormCheck.Invalid(FormError.MissingKey)

        method is ConnectionMethod.ApiKey ->
            FormCheck.Valid(CredentialInput.ApiKey(label, origin, Secret(key.value.trim().toCharArray())))

        else -> FormCheck.Valid(CredentialInput.Existing(label, origin))
    }
}

private fun ConnectWizardScreenState.selectMethod(id: String): ConnectWizardScreenState {
    val row = methods.firstOrNull { it.id == id } ?: return this
    return copy(selectedMethod = id, form = CredentialForm(row.provider, row.origin), formError = null)
}

internal fun ConnectWizardScreenState.reflect(state: ConnectWizardState): ConnectWizardScreenState = when (state) {
    ConnectWizardState.Idle -> copy(step = WizardStep.Engine, isBusy = true)

    is ConnectWizardState.ChoosingEngine -> copy(
        step = WizardStep.Engine,
        engines = state.engines?.map { it.toRow() }?.toImmutableList(),
        isBusy = state.engines == null && state.failure == null,
        failure = state.failure?.toUi(),
    )

    is ConnectWizardState.ChoosingMethod -> withMethods(state.engine.descriptor.title, state.engine.methods())
        .copy(step = WizardStep.Method, isBusy = false, failure = state.failure?.toUi())

    is ConnectWizardState.Connecting -> withMethods(state.engine.descriptor.title, state.engine.methods())
        .copy(step = WizardStep.Method, isBusy = true, failure = null)

    is ConnectWizardState.ChoosingModels -> copy(
        step = WizardStep.Models,
        form = form.copy(key = SecretText()),
        models = state.models?.map { it.toRow(it.target.model in state.selected) }?.toImmutableList(),
        isBusy = state.models == null && state.failure == null,
        failure = state.failure?.toUi(),
    )

    is ConnectWizardState.Saving -> copy(step = WizardStep.Models, isBusy = true, failure = null)

    is ConnectWizardState.RollingBack -> copy(step = WizardStep.Models, isBusy = true, failure = null)

    is ConnectWizardState.Finished, ConnectWizardState.Cancelled -> copy(step = WizardStep.Done, isBusy = true)
}

private fun EngineInfo.methods(): List<MethodRowUi> = descriptor.connectionMethods.map { it.toRow() }

/** Keeps the selected method and its form while the method list stays the same. */
private fun ConnectWizardScreenState.withMethods(title: String, rows: List<MethodRowUi>): ConnectWizardScreenState {
    val base = copy(engineTitle = title, methods = rows.toImmutableList())
    val isKept = selectedMethod != null && rows.any { it.id == selectedMethod }
    return if (isKept || rows.isEmpty()) base else base.selectMethod(rows.first().id)
}
