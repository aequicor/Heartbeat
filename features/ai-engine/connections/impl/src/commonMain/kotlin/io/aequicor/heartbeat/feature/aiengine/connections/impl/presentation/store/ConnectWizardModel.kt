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
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointBaseUrl
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.canonicalBaseUrl
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.canonicalOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionCheck
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.api.WizardAdmission
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.ConnectWizardScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineOwnedJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.isCompatibleOriginAllowed
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce

/** Wizard steps as shown to the user. */
enum class WizardStep { Engine, Runtime, Method, Models, Done }

/** User-visible installation sources. */
enum class RuntimeSourceUi { System, Managed, Bundled }

/** Source represented by the current, still uncommitted runtime choice. */
enum class RuntimeChoiceUi { System, Managed, Bundled, Custom }

/** Admission status of the connection's exact saved route, shown before model selection. */
enum class AdmissionUi { Checking, Allowed, Unknown, Incompatible }

/** Form validation problems found before the machine is asked to connect. */
enum class FormError { MissingKey, InvalidOrigin, InsecureOrigin }

/** Typed key; never printed. */
@Immutable
data class SecretText(val value: String = "") {
    override fun toString(): String = "SecretText(***)"
}

/** A user-selected local path; screen logging must not disclose it. */
@Immutable
data class RuntimePath(val value: String) {
    override fun toString(): String = "RuntimePath(***)"
}

/** Exact profile job selected for cancellation; never rendered or logged. */
@Immutable
data class RuntimeJobHandle(val value: EngineOwnedJob) {
    override fun toString(): String = "RuntimeJobHandle"
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
    val selectedMethod: String? = null,
    val selectedGroup: AccessGroupUi? = null,
    val form: CredentialForm = CredentialForm(),
    val formError: FormError? = null,
    val models: ImmutableList<ModelRowUi>? = null,
    val modelQuery: String = "",
    val isBusy: Boolean = true,
    val isCancelAllowed: Boolean = true,
    val isNewConnection: Boolean = true,
    val isFinishing: Boolean = false,
    val failure: FailureUi? = null,
    val preparation: EnginePreparationUi? = null,
    val runtimeJobProgress: EnginePreparationUi? = null,
    val hasRuntimeStep: Boolean = false,
    val isRuntimePickerAvailable: Boolean = false,
    val isSystemRuntimeAvailable: Boolean = false,
    val isManagedRuntimeAvailable: Boolean = false,
    val isBundledRuntimeAvailable: Boolean = false,
    val isManagedInstallationAvailable: Boolean = false,
    val hasCheckedLocalRuntime: Boolean = false,
    val runtimeCancellationTarget: RuntimeJobHandle? = null,
    val hasRuntimeChoice: Boolean = false,
    val selectedRuntimeChoice: RuntimeChoiceUi? = null,
    val isRuntimeMissing: Boolean = false,
    val runtimePath: RuntimePath? = null,
    val isRuntimeUsable: Boolean = false,
    /** Null outside the model step; only an allowed probe may enable finishing. */
    val admission: AdmissionUi? = null,
) : MVIState

/** User events of the wizard. */
sealed interface ConnectWizardScreenIntent : MVIIntent {
    /** Picks an engine on the first step. */
    data class ChooseEngine(val id: String) : ConnectWizardScreenIntent

    /** Opens the native executable picker. */
    data object PickRuntime : ConnectWizardScreenIntent

    /** Checks the local installation before offering other sources. */
    data object CheckLocalRuntime : ConnectWizardScreenIntent

    /** Accepts the inspected runtime choice. */
    data object UseRuntime : ConnectWizardScreenIntent

    /** Starts installation of a managed runtime. */
    data object InstallManagedRuntime : ConnectWizardScreenIntent

    /** Cancels this wizard's active installation without closing the wizard. */
    data class CancelRuntimeInstallation(val handle: RuntimeJobHandle) : ConnectWizardScreenIntent

    /** Inspects the declared runtime source. */
    data class InspectRuntimeSource(val source: RuntimeSourceUi) : ConnectWizardScreenIntent

    /** Picks a provider/method; resets the form to its defaults. */
    data class SelectMethod(val id: String) : ConnectWizardScreenIntent

    /** Opens a declared access category. */
    data class SelectGroup(val group: AccessGroupUi) : ConnectWizardScreenIntent

    /** Edits the connection name. */
    data class EditLabel(val value: String) : ConnectWizardScreenIntent

    /** Edits the endpoint of an editable method. */
    data class EditOrigin(val value: String) : ConnectWizardScreenIntent

    /** Edits the API key; [toString] never prints it. */
    data class EditKey(val value: String) : ConnectWizardScreenIntent {
        override fun toString(): String = "EditKey(***)"
    }

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

    /** System back; the machine decides per step (see [ConnectWizardIntent.Public.Dismiss]). */
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
    facade: EngineFacade,
) {
    private val log = Log.tag("ConnectWizardModel")
    private val sentKeys = mutableListOf<Secret>()

    val store = factory.create<ConnectWizardScreenState, ConnectWizardScreenIntent, ConnectWizardScreenAction>(
        "ConnectWizard",
        ConnectWizardScreenState().reflect(machine.state.value),
        onError = { copy(isBusy = false, failure = FailureUi.Unknown) },
    ) {
        init {
            launch {
                combine(machine.state, facade.engines.state) { state, engines ->
                    val id = when (state) {
                        is ConnectWizardState.ChoosingMethod -> state.engine.descriptor.id

                        is ConnectWizardState.PreparingRuntime -> state.engine.descriptor.id

                        is ConnectWizardState.ChoosingRuntime -> state.engine.descriptor.id

                        is ConnectWizardState.Connecting -> state.engine.descriptor.id

                        is ConnectWizardState.CheckingAdmission -> state.engine

                        is ConnectWizardState.ChoosingModels -> state.engine

                        is ConnectWizardState.Saving -> state.engine

                        ConnectWizardState.Idle,
                        ConnectWizardState.Cancelled,
                        is ConnectWizardState.ChoosingEngine,
                        is ConnectWizardState.RollingBack,
                        is ConnectWizardState.Finished,
                        -> null
                    }
                    engines.firstOrNull { it.descriptor.id == id }?.preparation?.toUi()
                }.distinctUntilChanged().collect { setup ->
                    log.v { "Wizard runtime preparation changed" }
                    updateState { copy(preparation = setup) }
                }
            }
            // Closing follows the terminal machine state for the whole store lifetime, so a result reached while
            // the screen is not subscribed is still delivered once it is back.
            launch {
                val end = machine.state.first {
                    it is ConnectWizardState.Finished || it is ConnectWizardState.Cancelled
                }
                action(ConnectWizardScreenAction.Close((end as? ConnectWizardState.Finished)?.binding?.value))
            }
            sendTo(machine, ConnectWizardIntent.Public.Start(route.engine))
        }
        reflect(machine) { reflect(it) }
        reduce { intent ->
            when (intent) {
                is ConnectWizardScreenIntent.ChooseEngine ->
                    sendTo(machine, ConnectWizardIntent.Public.ChooseEngine(EngineId(intent.id)))

                ConnectWizardScreenIntent.PickRuntime -> sendTo(machine, ConnectWizardIntent.Public.PickRuntimeFile)

                ConnectWizardScreenIntent.CheckLocalRuntime ->
                    sendTo(machine, ConnectWizardIntent.Public.CheckLocalRuntime)

                ConnectWizardScreenIntent.UseRuntime -> sendTo(machine, ConnectWizardIntent.Public.UseRuntimeChoice)

                ConnectWizardScreenIntent.InstallManagedRuntime ->
                    sendTo(machine, ConnectWizardIntent.Public.InstallManagedRuntime)

                is ConnectWizardScreenIntent.CancelRuntimeInstallation ->
                    sendTo(machine, ConnectWizardIntent.Public.CancelRuntimeInstallation(intent.handle.value))

                is ConnectWizardScreenIntent.InspectRuntimeSource ->
                    sendTo(
                        machine,
                        ConnectWizardIntent.Public.InspectRuntimeSource(
                            when (intent.source) {
                                RuntimeSourceUi.System -> RuntimePreference.System
                                RuntimeSourceUi.Managed -> RuntimePreference.Managed
                                RuntimeSourceUi.Bundled -> RuntimePreference.Bundled
                            },
                        ),
                    )

                is ConnectWizardScreenIntent.SelectMethod -> updateState { selectMethod(intent.id) }

                is ConnectWizardScreenIntent.SelectGroup -> updateState { selectGroup(intent.group) }

                is ConnectWizardScreenIntent.EditLabel ->
                    updateState { copy(form = form.copy(label = intent.value), formError = null) }

                is ConnectWizardScreenIntent.EditOrigin ->
                    updateState { copy(form = form.copy(origin = intent.value), formError = null) }

                is ConnectWizardScreenIntent.EditKey ->
                    updateState { copy(form = form.copy(key = SecretText(intent.value)), formError = null) }

                ConnectWizardScreenIntent.Connect -> submit()

                is ConnectWizardScreenIntent.SearchModels -> updateState { copy(modelQuery = intent.query) }

                is ConnectWizardScreenIntent.ToggleModel ->
                    sendTo(machine, ConnectWizardIntent.Public.ToggleModel(ModelId(intent.id)))

                is ConnectWizardScreenIntent.SelectAllModels ->
                    sendTo(machine, ConnectWizardIntent.Public.SelectAllModels(intent.isSelected))

                ConnectWizardScreenIntent.Retry -> sendTo(machine, ConnectWizardIntent.Public.Retry)

                ConnectWizardScreenIntent.Back -> back(isSystem = false)

                ConnectWizardScreenIntent.Finish -> sendTo(machine, ConnectWizardIntent.Public.Finish)

                ConnectWizardScreenIntent.Cancel -> sendTo(machine, ConnectWizardIntent.Public.Cancel)

                ConnectWizardScreenIntent.SystemBack -> back(isSystem = true)
            }
        }
    }

    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        ConnectWizardScreenState,
        ConnectWizardScreenIntent,
        ConnectWizardScreenAction,
    >.back(
        isSystem: Boolean,
    ) {
        var isHandled = false
        updateState {
            if (step != WizardStep.Method || selectedGroup == null || isBusy) return@updateState this
            isHandled = true
            if (selectedMethod != null && methods.count { it.accessGroup == selectedGroup } > 1) {
                copy(selectedMethod = null, form = CredentialForm(), formError = null)
            } else {
                copy(selectedGroup = null, selectedMethod = null, form = CredentialForm(), formError = null)
            }
        }
        if (!isHandled) {
            sendTo(machine, if (isSystem) ConnectWizardIntent.Public.Dismiss else ConnectWizardIntent.Public.Back)
        }
    }

    init {
        scope.onClose { sentKeys.forEach(Secret::close) }
        store.start(scope.coroutineScope)
    }

    /** Validates the form and hands the key to the machine as an owned Secret, clearing it from the screen. */
    // PipelineContext is FlowMVI's pipeline receiver (a CoroutineScope); store DSL functions extend it the same way.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PipelineContext<
        ConnectWizardScreenState,
        ConnectWizardScreenIntent,
        ConnectWizardScreenAction,
    >.submit() {
        val choosing = machine.state.value as? ConnectWizardState.ChoosingMethod
        if (choosing == null) {
            log.w { "submit ignored: not on the method step" }
            return
        }
        var screen = ConnectWizardScreenState()
        withState { screen = this }
        val method = choosing.engine.descriptor.connectionMethods.firstOrNull { it.id.value == screen.selectedMethod }
        if (method == null) {
            log.w { "submit ignored: no method selected" }
            return
        }
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
            log.w { "submit not accepted result=$result" }
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
    val endpoint = when {
        method.isPathEditable -> canonicalBaseUrl(origin)
        method.isOriginEditable -> canonicalOrigin(origin)?.let(::EndpointBaseUrl)
        else -> EndpointBaseUrl(method.origin)
    }
    val label = label.trim().ifEmpty { method.provider.title }
    return when {
        endpoint == null -> FormCheck.Invalid(FormError.InvalidOrigin)

        // A managed key is never sent unencrypted over a network; catch it here rather than in the engine.
        CompatibleProtocol.entries.any { it.provider.id == method.provider.id } &&
            !isCompatibleOriginAllowed(endpoint.origin) ->
            FormCheck.Invalid(FormError.InsecureOrigin)

        method is ConnectionMethod.ApiKey && key.value.isBlank() -> FormCheck.Invalid(FormError.MissingKey)

        method is ConnectionMethod.ApiKey -> FormCheck.Valid(
            CredentialInput.ApiKey(
                label,
                endpoint.origin,
                Secret(key.value.trim().toCharArray()),
                endpoint.basePath,
            ),
        )

        else -> FormCheck.Valid(CredentialInput.Existing(label, endpoint.origin))
    }
}

private fun ConnectWizardScreenState.selectMethod(id: String): ConnectWizardScreenState {
    if (isBusy) return this
    val row = methods.firstOrNull { it.id == id && it.accessGroup == selectedGroup } ?: return this
    return copy(selectedMethod = id, form = CredentialForm(row.provider, row.origin), formError = null)
}

private fun ConnectWizardScreenState.selectGroup(group: AccessGroupUi): ConnectWizardScreenState {
    val rows = methods.filter { it.accessGroup == group }
    if (rows.isEmpty() || isBusy) return this
    val base = copy(selectedGroup = group, selectedMethod = null, form = CredentialForm(), formError = null)
    return if (rows.size == 1) base.selectMethod(rows.single().id) else base
}

internal fun ConnectWizardScreenState.reflect(state: ConnectWizardState): ConnectWizardScreenState = when (state) {
    ConnectWizardState.Idle -> ConnectWizardScreenState()

    // Leaving the method step drops the form, so a typed key never outlives it or reaches another engine.
    is ConnectWizardState.ChoosingEngine -> reflectEngines(state)

    is ConnectWizardState.PreparingRuntime -> copy(
        step = WizardStep.Runtime,
        engineTitle = state.engine.descriptor.title,
        isBusy = state.failure == null,
        isCancelAllowed = true,
        runtimeJobProgress = null,
        admission = null,
        failure = state.failure?.toUi(),
    )

    is ConnectWizardState.ChoosingRuntime -> reflectRuntime(state)

    is ConnectWizardState.ChoosingMethod -> withMethods(state.engine.descriptor.title, state.engine.methods())
        .copy(
            step = WizardStep.Method,
            hasRuntimeStep = state.runtimeOptions != null,
            isBusy = state.isPickingRuntime || state.check == ConnectionCheck.Running,
            isCancelAllowed = true,
            isNewConnection = true,
            isFinishing = false,
            runtimeJobProgress = null,
            admission = null,
            failure = state.failure?.toUi(),
        )

    is ConnectWizardState.Connecting -> withMethods(state.engine.descriptor.title, state.engine.methods())
        .copy(
            step = WizardStep.Method,
            hasRuntimeStep = state.runtimeOptions != null,
            isBusy = true,
            isCancelAllowed = false,
            isNewConnection = true,
            isFinishing = false,
            runtimeJobProgress = null,
            admission = null,
            failure = null,
        )

    is ConnectWizardState.CheckingAdmission -> reflectAdmission(state)

    is ConnectWizardState.ChoosingModels -> reflectModels(state)

    is ConnectWizardState.Saving -> reflectSaving(state)

    is ConnectWizardState.RollingBack -> reflectRollingBack()

    is ConnectWizardState.Finished, ConnectWizardState.Cancelled ->
        terminal()
}

private fun ConnectWizardScreenState.reflectAdmission(
    state: ConnectWizardState.CheckingAdmission,
): ConnectWizardScreenState = copy(
    step = WizardStep.Models,
    form = form.copy(key = SecretText()),
    models = null,
    admission = state.admission.toUi(),
    isBusy = state.admission == WizardAdmission.Checking,
    isCancelAllowed = true,
    isNewConnection = state.connection.isCreated,
    isFinishing = false,
    runtimeJobProgress = null,
    failure = state.failure?.toUi(),
)

private fun ConnectWizardScreenState.reflectModels(
    state: ConnectWizardState.ChoosingModels,
): ConnectWizardScreenState = copy(
    step = WizardStep.Models,
    form = form.copy(key = SecretText()),
    models = state.models?.map { it.toRow(it.target.model in state.selected) }?.toImmutableList(),
    admission = AdmissionUi.Allowed,
    isBusy = state.models == null && state.failure == null,
    isCancelAllowed = true,
    isNewConnection = state.connection.isCreated,
    isFinishing = false,
    runtimeJobProgress = null,
    failure = state.failure?.toUi(),
)

private fun ConnectWizardScreenState.reflectSaving(state: ConnectWizardState.Saving): ConnectWizardScreenState = copy(
    step = WizardStep.Models,
    admission = AdmissionUi.Allowed,
    isBusy = true,
    isCancelAllowed = false,
    isNewConnection = state.connection.isCreated,
    isFinishing = true,
    runtimeJobProgress = null,
    failure = null,
)

private fun ConnectWizardScreenState.reflectRollingBack(): ConnectWizardScreenState = copy(
    step = WizardStep.Models,
    admission = null,
    isBusy = true,
    isCancelAllowed = false,
    isFinishing = false,
    failure = null,
)

private fun ConnectWizardScreenState.reflectEngines(state: ConnectWizardState.ChoosingEngine) = copy(
    step = WizardStep.Engine,
    selectedMethod = null,
    selectedGroup = null,
    form = CredentialForm(),
    formError = null,
    engines = state.engines?.map { it.toRow() }?.toImmutableList(),
    isBusy = state.engines == null && state.failure == null,
    isCancelAllowed = true,
    isNewConnection = true,
    isFinishing = false,
    isRuntimePickerAvailable = false,
    isSystemRuntimeAvailable = false,
    isManagedRuntimeAvailable = false,
    isBundledRuntimeAvailable = false,
    isManagedInstallationAvailable = false,
    hasCheckedLocalRuntime = false,
    runtimeCancellationTarget = null,
    runtimeJobProgress = null,
    hasRuntimeStep = false,
    hasRuntimeChoice = false,
    selectedRuntimeChoice = null,
    isRuntimeMissing = false,
    runtimePath = null,
    isRuntimeUsable = false,
    admission = null,
    failure = state.failure?.toUi(),
)

private fun ConnectWizardScreenState.reflectRuntime(state: ConnectWizardState.ChoosingRuntime) = copy(
    step = WizardStep.Runtime,
    hasRuntimeStep = true,
    engineTitle = state.engine.descriptor.title,
    isRuntimePickerAvailable = state.options.isFilePickerAvailable,
    isSystemRuntimeAvailable = RuntimePreference.System in state.options.sources,
    isManagedRuntimeAvailable = RuntimePreference.Managed in state.options.sources,
    isBundledRuntimeAvailable = RuntimePreference.Bundled in state.options.sources,
    isManagedInstallationAvailable = state.options.isManagedInstallAvailable,
    hasCheckedLocalRuntime = state.hasCheckedLocal,
    runtimeCancellationTarget = state.cancellationTarget()?.takeUnless { state.isCancellingInstallation }
        ?.let(::RuntimeJobHandle),
    runtimeJobProgress = state.runtimeJob.installationProgress(),
    hasRuntimeChoice = state.choice != null,
    selectedRuntimeChoice = state.choice?.let { choice ->
        when {
            !choice.settings.executable.isNullOrBlank() -> RuntimeChoiceUi.Custom
            choice.settings.runtime == RuntimePreference.System -> RuntimeChoiceUi.System
            choice.settings.runtime == RuntimePreference.Managed -> RuntimeChoiceUi.Managed
            choice.settings.runtime == RuntimePreference.Bundled -> RuntimeChoiceUi.Bundled
            else -> null
        }
    },
    isRuntimeMissing = state.choice?.inspection?.installation?.source == InstallSource.Missing,
    runtimePath = (state.choice?.settings?.executable ?: state.choice?.inspection?.installation?.path)
        ?.let(::RuntimePath),
    isRuntimeUsable = state.choice?.inspection?.let {
        it.installation.isRunnable && it.installation.source != InstallSource.Missing &&
            it.compatibility?.decision?.isBaselineAllowed == true
    } == true,
    isBusy = state.isPicking || state.runtimeJob?.phase?.isFinished == false,
    isCancelAllowed = true,
    admission = null,
    failure = state.failure?.toUi(),
)

private fun ConnectWizardState.ChoosingRuntime.cancellationTarget(): EngineOwnedJob? =
    if (isInstalling && isInstallationStarted) {
        installationHandle
    } else {
        runtimeJob?.takeIf {
            !it.phase.isFinished && (it.action == EngineAction.Install || it.action == EngineAction.Update)
        }?.handle
    }

private fun ConnectWizardScreenState.terminal(): ConnectWizardScreenState = copy(
    step = WizardStep.Done,
    form = CredentialForm(),
    selectedMethod = null,
    selectedGroup = null,
    admission = null,
    isBusy = true,
    isCancelAllowed = false,
    isFinishing = false,
)

internal fun WizardAdmission.toUi(): AdmissionUi = when (this) {
    WizardAdmission.Checking -> AdmissionUi.Checking
    WizardAdmission.Allowed -> AdmissionUi.Allowed
    is WizardAdmission.Refused -> AdmissionUi.Incompatible
    is WizardAdmission.Unknown -> AdmissionUi.Unknown
}

private fun EngineInfo.methods(): List<MethodRowUi> = descriptor.connectionMethods.map { it.toRow() }

/** Keeps the selected method and its form while the method list stays the same. */
private fun ConnectWizardScreenState.withMethods(title: String, rows: List<MethodRowUi>): ConnectWizardScreenState {
    val base = copy(engineTitle = title, methods = rows.toImmutableList())
    val isKept = selectedGroup == null || rows.any { it.accessGroup == selectedGroup }
    if (!isKept) return base.copy(selectedGroup = null, selectedMethod = null, form = CredentialForm())
    if (selectedMethod != null && rows.none { it.id == selectedMethod && it.accessGroup == selectedGroup }) {
        return base.copy(selectedMethod = null, form = CredentialForm())
    }
    return base
}
