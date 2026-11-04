package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionCheck
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.api.NewConnection
import io.aequicor.heartbeat.feature.aiengine.connections.api.WizardAdmission
import io.aequicor.heartbeat.feature.aiengine.connections.api.WizardLaunchRollback
import io.aequicor.heartbeat.feature.aiengine.connections.api.WizardRuntimeChoice
import io.aequicor.heartbeat.feature.aiengine.connections.api.WizardRuntimeOptions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineActionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineOwnedJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineRouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableEngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableEngineRouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.actions
import io.aequicor.heartbeat.feature.aiengine.facade.api.create
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Engine-side services of the connection screens; [management] is off unless engine management is enabled. */
data class EngineServices(
    val facade: EngineFacade,
    val sources: AuthSources,
    val management: EngineManagement = UnavailableEngineManagement(),
    val admission: EngineRouteAdmission = UnavailableEngineRouteAdmission(),
)

/** Executes wizard effects. Typed keys are closed after use whatever the outcome. */
internal class ConnectWizardEffects(
    private val services: EngineServices,
    private val selections: ModelSelections,
    private val checks: ModelChecks,
    private val runtimePicker: RuntimeFilePicker = UnavailableRuntimeFilePicker,
) : EffectHandler<ConnectWizardEffect, ConnectWizardIntent> {
    private val log = Log.tag("ConnectWizardEffects")

    override suspend fun handle(effect: ConnectWizardEffect, machine: EffectScope<ConnectWizardIntent>) {
        when (effect) {
            ConnectWizardEffect.ObserveEngines -> services.facade.engines.state.collect {
                machine.send(ConnectWizardIntent.Internal.EnginesChanged(it))
            }

            is ConnectWizardEffect.LoadRuntimeOptions -> loadRuntimeOptions(effect.engine, machine)

            is ConnectWizardEffect.ObserveRuntimeJob -> observeRuntimeJob(effect.engine, machine)

            is ConnectWizardEffect.CheckLocalRuntime,
            is ConnectWizardEffect.PickRuntimeFile,
            is ConnectWizardEffect.InspectRuntimeSource,
            -> inspectRuntime(effect, machine)

            is ConnectWizardEffect.InstallManagedRuntime -> installManagedRuntime(effect.engine, machine)

            is ConnectWizardEffect.CancelRuntimeInstallation -> cancelRuntimeInstallation(effect.handle, machine)

            is ConnectWizardEffect.Connect -> {
                val prepared = try {
                    connect(effect.engine, effect.method, effect.credential, effect.runtimeChoice)
                } finally {
                    (effect.credential as? CredentialInput.ApiKey)?.key?.close()
                }
                deliver(effect.engine, prepared, machine)
            }

            is ConnectWizardEffect.CheckConnection -> {
                val models = try {
                    check(effect)
                } finally {
                    (effect.credential as? CredentialInput.ApiKey)?.key?.close()
                }
                machine.send(ConnectWizardIntent.Internal.ConnectionChecked(ConnectionCheck.Succeeded(models)))
            }

            is ConnectWizardEffect.AssessAdmission -> {
                log.i { "assess route admission engine=${effect.engine.value}" }
                val admission = services.admission.assess(effect.engine, effect.binding).toWizardAdmission()
                log.i { "route admission engine=${effect.engine.value} status=${admission.statusLabel()}" }
                machine.send(ConnectWizardIntent.Internal.AdmissionAssessed(admission))
            }

            is ConnectWizardEffect.DiscoverModels -> {
                log.i { "discover models engine=${effect.engine.value}" }
                val snapshot = services.facade.models.refresh(effect.engine, effect.binding)
                log.d { "discovered models count=${snapshot.models.size}" }
                machine.send(ConnectWizardIntent.Internal.ModelsLoaded(snapshot.models))
            }

            is ConnectWizardEffect.SaveModels -> saveModels(effect, machine)

            is ConnectWizardEffect.Rollback -> {
                // A compensation must finish even if the wizard closes meanwhile.
                withContext(NonCancellable) { rollbackWizard(effect.connection) }
                machine.send(ConnectWizardIntent.Internal.RolledBack)
            }
        }
    }

    private suspend fun inspectRuntime(effect: ConnectWizardEffect, machine: EffectScope<ConnectWizardIntent>) {
        when {
            effect is ConnectWizardEffect.CheckLocalRuntime -> checkLocalRuntime(effect, machine)

            effect is ConnectWizardEffect.PickRuntimeFile -> {
                val selected = selectRuntimeFile(effect.engine, services.management, runtimePicker)
                val choice = (selected as? RuntimeFileSelection.Selected)?.let {
                    WizardRuntimeChoice(it.expected, it.settings, it.inspection)
                }
                machine.send(ConnectWizardIntent.Internal.RuntimeFilePicked(choice))
            }

            effect is ConnectWizardEffect.InspectRuntimeSource ->
                inspectRuntimeSource(effect.engine, effect.source, machine)

            else -> error("Unexpected runtime inspection effect")
        }
    }

    private suspend fun cancelRuntimeInstallation(handle: EngineOwnedJob, machine: EffectScope<ConnectWizardIntent>) {
        if (handle.cancel() == JobPhase.Cancelled) {
            machine.send(ConnectWizardIntent.Internal.RuntimeInstallationCancelled)
        } else {
            machine.send(ConnectWizardIntent.Internal.RuntimeInstallationAlreadyFinished)
        }
    }

    private suspend fun checkLocalRuntime(
        effect: ConnectWizardEffect.CheckLocalRuntime,
        machine: EffectScope<ConnectWizardIntent>,
    ) {
        val source = effect.source
        if (source == null) {
            machine.send(ConnectWizardIntent.Internal.RuntimeFilePicked(null))
        } else {
            inspectRuntimeSource(effect.engine, source, machine)
        }
    }

    private suspend fun inspectRuntimeSource(
        engine: EngineId,
        source: RuntimePreference,
        machine: EffectScope<ConnectWizardIntent>,
    ) {
        val snapshot = services.management.snapshotLaunchSettings(engine)
        val settings = snapshot.settings.copy(executable = null, runtime = source)
        val inspection = services.management.inspectCandidate(engine, snapshot, settings)
        machine.send(
            ConnectWizardIntent.Internal.RuntimeFilePicked(WizardRuntimeChoice(snapshot, settings, inspection)),
        )
    }

    private suspend fun loadRuntimeOptions(engine: EngineId, machine: EffectScope<ConnectWizardIntent>) {
        val managed = services.management.state.value.takeIf { it.isEnabled }?.engine(engine)
        val options = managed?.spec?.takeUnless { it.install == InstallSupport.BuiltIn }?.let { spec ->
            val sources = if (LaunchOption.Runtime in spec.launch.options) {
                spec.launch.runtimes.intersect(
                    setOf(RuntimePreference.System, RuntimePreference.Managed, RuntimePreference.Bundled),
                )
            } else {
                emptySet()
            }
            WizardRuntimeOptions(
                isFilePickerAvailable = runtimePicker.isAvailable && LaunchOption.Executable in spec.launch.options,
                isManagedInstallAvailable = EngineActionKind.Install in managed.actions() &&
                    RuntimePreference.Managed in sources,
                sources = sources,
                initialJob = managed.job,
            ).takeIf { it.isFilePickerAvailable || it.isManagedInstallAvailable || it.sources.isNotEmpty() }
        }
        machine.send(ConnectWizardIntent.Internal.RuntimeOptionsLoaded(options))
    }

    private suspend fun observeRuntimeJob(engine: EngineId, machine: EffectScope<ConnectWizardIntent>) {
        services.management.state.map { state ->
            val entry = state.takeIf { it.isEnabled }?.engine(engine)
            val isManagedSupported = entry != null && LaunchOption.Runtime in entry.spec.launch.options &&
                RuntimePreference.Managed in entry.spec.launch.runtimes
            ConnectWizardIntent.Internal.RuntimeJobChanged(
                entry?.job,
                isManagedSupported && entry.actions().contains(EngineActionKind.Install),
            )
        }.distinctUntilChanged().collect { machine.send(it) }
    }

    private suspend fun installManagedRuntime(engine: EngineId, machine: EffectScope<ConnectWizardIntent>) {
        var owned: EngineOwnedJob? = null
        services.management.startOwned(engine, EngineAction.Install) { owned = it }
        machine.send(ConnectWizardIntent.Internal.RuntimeInstallationStarted(requireNotNull(owned)))
        // The profile owns the job; leaving this step cancels only the waiter.
        val result = owned.await()
        if (result == JobPhase.Cancelled) {
            machine.send(ConnectWizardIntent.Internal.RuntimeInstallationCancelled)
            return
        }
        if (result != JobPhase.Succeeded) {
            val failure = ((result as? JobPhase.Failed)?.failure as? ManagementFailure.Engine)?.failure
            throw EngineException(failure ?: EngineFailure.Unknown())
        }
        val snapshot = services.management.snapshotLaunchSettings(engine)
        val settings = snapshot.settings.copy(executable = null, runtime = RuntimePreference.Managed)
        val inspection = services.management.inspectCandidate(engine, snapshot, settings)
        val choice = WizardRuntimeChoice(snapshot, settings, inspection)
        machine.send(ConnectWizardIntent.Internal.RuntimeFilePicked(choice))
    }

    private suspend fun saveModels(effect: ConnectWizardEffect.SaveModels, machine: EffectScope<ConnectWizardIntent>) {
        if (effect.removedTargets.isNotEmpty()) checks.revoke(effect.removedTargets)
        val result = checks.saveAndCheck(
            effect.checkTargets,
            onSaved = { machine.send(ConnectWizardIntent.Internal.Checking(effect.operation)) },
        ) {
            selections.update { latest ->
                val added = effect.models - effect.previous
                val removed = effect.previous - effect.models
                latest.withEnabled(effect.binding, latest.enabled(effect.binding) + added - removed)
            }
        }
        val completion = when (result) {
            is ModelCheckBatchResult.Completed -> ConnectWizardIntent.Internal.Saved(effect.operation)

            is ModelCheckBatchResult.TimedOut -> ConnectWizardIntent.Internal.Saved(
                effect.operation,
                isSavePending = result.saved == null,
            )

            is ModelCheckBatchResult.Failed -> if (result.saved == null) {
                ConnectWizardIntent.Internal.SaveFailed(EngineFailure.Unknown(), effect.operation)
            } else {
                ConnectWizardIntent.Internal.Saved(effect.operation)
            }
        }
        machine.send(completion)
    }

    /**
     * Discovers models through an existing CLI binding or a temporary connection removed whatever the outcome.
     * The wizard may be left mid-check, so writing the connection is not cancellable: what was written is always
     * known and compensated according to its ownership.
     * The installation probe before it stays cancellable.
     */
    private suspend fun check(effect: ConnectWizardEffect.CheckConnection): Int {
        log.i { "check connection engine=${effect.engine.value} method=${effect.method.id.value}" }
        // A check never changes the engine's runtime, but a host application method still needs the app's runtime.
        if (effect.method.hostApp != null) requireHostRuntime(effect.engine) else requireAvailable(effect.engine)
        signInIfNeeded(effect.engine, effect.method, mayChangeRuntime = false, RuntimeSwitch())
        var created: NewConnection? = null
        try {
            val connection = withContext(NonCancellable) {
                write(effect.engine, effect.method, effect.credential).also { created = it }
            }
            // A wizard left while connecting stops here; `finally` removes what was created.
            currentCoroutineContext().ensureActive()
            val count = services.facade.models.refresh(effect.engine, connection.binding).models.size
            log.i { "connection check passed models=$count" }
            return count
        } finally {
            created?.let { withContext(NonCancellable) { rollback(it) } }
        }
    }

    private suspend fun connect(
        engine: EngineId,
        method: ConnectionMethod,
        credential: CredentialInput,
        choice: WizardRuntimeChoice?,
    ): PreparedConnection {
        log.i { "connect engine=${engine.value} method=${method.id.value}" }
        val hostApp = method.hostApp
        val switch = RuntimeSwitch()
        var connection: NewConnection? = null
        var isApplied = false
        try {
            if (choice != null) {
                // CLI login cannot yet bind its process to this inspection; do not run it on this path.
                if (method is ConnectionMethod.CliLogin || hostApp != null) {
                    throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
                }
                applyRuntimeChoice(engine, choice, switch)
            }
            // The app's runtime replaces the current runtime only after its own inspection.
            if (hostApp != null) {
                switch.current = requireHostRuntime(engine).savedLaunch
            } else {
                requireAvailable(engine)
            }
            signInIfNeeded(engine, method, mayChangeRuntime = true, switch)
            connection = write(engine, method, credential)
            currentCoroutineContext().ensureActive()
            if (hostApp != null) preferHostRuntime(engine, switch)
            currentCoroutineContext().ensureActive()
            isApplied = true
            val previous = switch.previous
            val receipt = switch.receipt
            val launchRollback = if (previous != null && receipt != null) {
                WizardLaunchRollback(engine, previous, receipt)
            } else {
                null
            }
            return PreparedConnection(connection.copy(launchRollback = launchRollback), switch)
        } finally {
            if (!isApplied) {
                withContext(NonCancellable) {
                    compensate(engine, connection, switch)
                }
            }
        }
    }

    private suspend fun applyRuntimeChoice(engine: EngineId, choice: WizardRuntimeChoice, switch: RuntimeSwitch) {
        val result = services.management.inspectCandidate(engine, choice.expected, choice.settings)
        if (result?.installation?.isRunnable != true || result.installation.source == InstallSource.Missing ||
            result.compatibility?.decision?.isBaselineAllowed != true
        ) {
            throw EngineException(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
        }
        services.management.configureIfUnchanged(engine, choice.expected, choice.settings) { receipt ->
            switch.previous = choice.expected.settings
            switch.receipt = receipt
            switch.current = receipt
        }
    }

    /**
     * A host application connection needs engine management, which saves the runtime preference, and a runtime of
     * the application to run. The wizard judges the bounded result of its own inspection, independently of
     * asynchronous management state publication.
     */
    private suspend fun requireHostRuntime(engine: EngineId): EngineInspection {
        val management = services.management
        if (!management.state.value.isEnabled) {
            log.w { "host application connection needs engine management engine=${engine.value}" }
            throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        val inspection = inspect(engine)
        if (inspection.hostApp?.hasRuntime != true) {
            log.w { "host application runtime not found engine=${engine.value}" }
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
        return inspection
    }

    /** Waits boundedly for the result of this specific inspection, never an unrelated state emission. */
    private suspend fun inspect(engine: EngineId): EngineInspection {
        log.i { "inspect engine=${engine.value}" }
        val inspection = withTimeoutOrNull(INSPECTION_WAIT_MILLIS) { services.management.inspect(engine) }
            ?: throw EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))
        if (inspection.engine != engine) throw EngineException(EngineFailure.Unknown())
        return inspection
    }

    /**
     * Signs the engine's CLI in when the method uses its login and the inspection found it signed out, so a connection
     * to an engine whose app is signed in needs no detour through the engine panel: the CLI opens the sign-in page
     * itself and the wizard waits for its outcome (a prompt for a pasted code is normal there, the callback still
     * completes the sign-in). A host application connection runs on the app's runtime from here on, so that runtime
     * is switched to and inspected first; a check ([mayChangeRuntime] false) never switches it and signs in only
     * where the runtime already fits. A failed, cancelled or timed out sign-in, and any management refusal, are
     * left to the binding check, which then reports the missing credentials.
     */
    private suspend fun signInIfNeeded(
        engine: EngineId,
        method: ConnectionMethod,
        mayChangeRuntime: Boolean,
        switch: RuntimeSwitch,
    ) {
        if (method !is ConnectionMethod.CliLogin || !services.management.state.value.isEnabled) return
        if (services.management.state.value.engine(engine)?.spec?.login.isCliLogin() != true) return
        try {
            var managed = inspect(engine)
            switch.accept(managed)
            if (method.hostApp != null && !managed.isOnHostRuntime()) {
                if (!mayChangeRuntime) {
                    log.i { "sign-in skipped, a check keeps the runtime engine=${engine.value}" }
                    return
                }
                preferHostRuntime(engine, switch)
                managed = inspect(engine)
                switch.accept(managed)
            }
            if (managed.login == LoginState.SignedOut) {
                signIn(engine, switch.current ?: changedLaunch())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            if (e.failure == EngineFailure.Session(SessionFailureReason.Changed)) throw e
            log.w(e) { "sign-in not possible engine=${engine.value}" }
        }
    }

    private suspend fun restoreSettings(engine: EngineId, receipt: LaunchSettingsSnapshot, settings: LaunchSettings) {
        attempt("restore the launch settings changed for the sign-in") {
            services.management.configureIfUnchanged(engine, receipt, settings) { }
        }
    }

    private fun changedLaunch(): Nothing = throw EngineException(EngineFailure.Session(SessionFailureReason.Changed))

    private fun RuntimeSwitch.accept(inspection: EngineInspection) {
        val snapshot = inspection.savedLaunch
        if (receipt != null && snapshot?.token !== receipt?.token) changedLaunch()
        current = snapshot
    }

    private fun EngineInspection.isOnHostRuntime(): Boolean {
        val settings = launch.settings
        return settings.runtime == RuntimePreference.HostApp && settings.executable.isNullOrBlank()
    }

    private suspend fun signIn(engine: EngineId, expected: LaunchSettingsSnapshot) {
        val management = services.management
        log.i { "sign in engine=${engine.value}" }
        var owned: EngineOwnedJob? = null
        var phase: JobPhase? = null
        try {
            management.startLoginIfUnchanged(engine, expected, LoginMethod.Browser) { owned = it }
            phase = withTimeoutOrNull(SIGN_IN_WAIT_MILLIS) { requireNotNull(owned).await() }
        } finally {
            withContext(NonCancellable) {
                if (phase == null) owned?.cancel()
                owned?.dismiss()
            }
        }
        when {
            phase == JobPhase.Succeeded -> log.i { "signed in engine=${engine.value}" }

            phase == null -> log.w { "sign in timed out engine=${engine.value}" }

            else -> log.w {
                "sign in did not complete engine=${engine.value} phase=${phase::class.simpleName.orEmpty()} " +
                    "failure=${(phase as? JobPhase.Failed)?.failure?.let { it::class.simpleName }.orEmpty()}"
            }
        }
    }

    /**
     * Saves the host application runtime as the engine's runtime instead of a custom executable; a pinned version
     * and the other settings stay. The runtime is a setting of the whole engine, so every connection of the engine
     * runs on it from now on (the wizard says so); removing the connection does not restore the previous choice.
     */
    private suspend fun preferHostRuntime(engine: EngineId, switch: RuntimeSwitch) {
        val snapshot = switch.current ?: changedLaunch()
        val settings = snapshot.settings
        val updated = settings.copy(executable = null, runtime = RuntimePreference.HostApp)
        services.management.configureIfUnchanged(engine, snapshot, updated) { receipt ->
            if (switch.previous == null) switch.previous = settings
            switch.receipt = receipt
            switch.current = receipt
        }
        log.i {
            "host application runtime preferred engine=${engine.value} previous=${settings.runtime} " +
                "replacedExecutable=${!settings.executable.isNullOrBlank()}"
        }
    }

    private suspend fun requireAvailable(engine: EngineId) {
        services.facade.engines.refresh(engine).availability.failure()?.let { throw EngineException(it) }
    }

    /** Reuses an existing CLI route, or creates a source and binds it with compensation on failure. */
    private suspend fun write(engine: EngineId, method: ConnectionMethod, credential: CredentialInput): NewConnection {
        existingLogin(engine, method)?.let { return it }
        val key = (credential as? CredentialInput.ApiKey)?.key
        val source = services.sources.create(
            method,
            credential.label,
            credential.origin,
            key,
            credential.basePath,
        )
        val binding = bindOrForget(engine, source.info.id)
        log.i { "connected engine=${engine.value}" }
        return NewConnection(binding.id, source.info.id)
    }

    /** The same native account location cannot have two bindings, regardless of the wizard's connection label. */
    private suspend fun existingLogin(engine: EngineId, method: ConnectionMethod): NewConnection? {
        if (method !is ConnectionMethod.CliLogin) return null
        for (binding in services.facade.bindings.state.value.filter { it.engine == engine }) {
            val source = services.sources.get(binding.authSource) as? AuthSource.CliLogin ?: continue
            if (source.owner == method.owner && source.location == method.location) {
                log.i { "reusing CLI connection engine=${engine.value} binding=${binding.id.value}" }
                val selected = selections.observe().first().enabled(binding.id)
                val connection = NewConnection(
                    binding.id,
                    source.info.id,
                    isCreated = false,
                    initialModels = selected,
                    isInitiallyEnabled = binding.isEnabled,
                )
                if (!binding.isEnabled) enableForWizard(connection)
                return connection
            }
        }
        return null
    }

    /** A cancelled enable must restore the route even if its persistence completed before cancellation surfaced. */
    private suspend fun enableForWizard(connection: NewConnection) {
        var isEnabled = false
        try {
            services.facade.bindings.setEnabled(connection.binding, true)
            currentCoroutineContext().ensureActive()
            isEnabled = true
        } finally {
            if (!isEnabled) withContext(NonCancellable) { rollback(connection) }
        }
    }

    /**
     * Hands the new connection to the wizard. If the wizard is gone (closed while connecting) or no longer waits
     * for it, nobody could roll it back later, so the connection is removed right away.
     */
    private suspend fun deliver(
        engine: EngineId,
        prepared: PreparedConnection,
        machine: EffectScope<ConnectWizardIntent>,
    ) {
        var isDelivered = false
        try {
            isDelivered = machine.send(
                ConnectWizardIntent.Internal.Connected(prepared.connection),
            ) == SendResult.Accepted
        } finally {
            if (!isDelivered) {
                log.w { "connection not accepted by the wizard, rolling it back" }
                withContext(NonCancellable) { compensate(engine, prepared.connection, prepared.switch) }
            }
        }
    }

    private suspend fun compensate(engine: EngineId, connection: NewConnection?, switch: RuntimeSwitch) =
        withContext(NonCancellable) {
            try {
                connection?.let { rollback(it) }
            } finally {
                val previous = switch.previous
                val receipt = switch.receipt
                if (previous != null && receipt != null) {
                    withContext(NonCancellable) { restoreSettings(engine, receipt, previous) }
                }
            }
        }

    private suspend fun rollbackWizard(connection: NewConnection) {
        try {
            rollback(connection)
        } finally {
            withContext(NonCancellable) {
                connection.launchRollback?.let { restoreSettings(it.engine, it.receipt, it.previous) }
            }
        }
    }

    /** Binds [source]; a binding that fails or is cancelled must not leave the new source behind. */
    private suspend fun bindOrForget(engine: EngineId, source: AuthSourceId): EngineBinding {
        var isBound = false
        try {
            return services.facade.bindings.connect(engine, source).also { isBound = true }
        } finally {
            if (!isBound) {
                log.w { "binding not created, forgetting the new source" }
                withContext(NonCancellable) { forgetQuietly(source) }
            }
        }
    }

    /**
     * Removes the binding, then its source, then its model choice. Each step runs even if a later one fails;
     * the source is kept only while the binding still exists, so a binding never points at a missing source.
     */
    private suspend fun rollback(connection: NewConnection) {
        if (connection.isCreated || !connection.isInitiallyEnabled) checks.revoke(connection.binding)
        if (!connection.isCreated) {
            if (!connection.isInitiallyEnabled) {
                attempt("restore the disabled CLI connection") {
                    services.facade.bindings.setEnabled(connection.binding, false)
                }
            }
            return
        }
        log.i { "roll back abandoned connection" }
        val isDisconnected = attempt("disconnect the binding; the connection stays visible in settings") {
            services.facade.bindings.disconnect(connection.binding)
        }
        if (isDisconnected) forgetQuietly(connection.source)
        attempt("clear the model choice of the removed binding") {
            selections.update { it.without(connection.binding) }
        }
    }

    private suspend fun forgetQuietly(source: AuthSourceId) {
        attempt("forget source id=${source.value}") { services.sources.forget(source) }
    }

    /** Runs one compensation step; a failure is logged and reported as false. */
    private suspend fun attempt(step: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "could not $step" }
        false
    }
}

/** Failure established by an explicit probe; Unknown does not block, the binding check decides. */
internal fun EngineAvailability.failure(): EngineFailure? = when (this) {
    EngineAvailability.Available, EngineAvailability.Unknown -> null
    EngineAvailability.UnsupportedPlatform -> EngineFailure.Engine(EngineFailureReason.Unavailable)
    is EngineAvailability.Unavailable -> failure
}

/**
 * Maps the facade's assessment to the wizard's fail-closed status: no policy or candidate is unknown, and only a
 * proven baseline is allowed. Typed problems are preserved for diagnostics and tests, never rendered as raw data.
 */
internal fun RouteAdmission.toWizardAdmission(): WizardAdmission = when (this) {
    RouteAdmission.Unavailable -> WizardAdmission.Unknown()

    is RouteAdmission.Assessed -> when {
        decision.isBaselineAllowed -> WizardAdmission.Allowed
        decision.status == Compatibility.Incompatible -> WizardAdmission.Refused(decision.problems)
        else -> WizardAdmission.Unknown(decision.problems)
    }
}

private fun WizardAdmission.statusLabel(): String = when (this) {
    WizardAdmission.Checking -> "checking"
    WizardAdmission.Allowed -> "allowed"
    is WizardAdmission.Refused -> "refused"
    is WizardAdmission.Unknown -> "unknown"
}

/** The launch settings a sign-in replaced, kept outside the sign-in so a cancelled wizard can still restore them. */
private class RuntimeSwitch {
    var previous: LaunchSettings? = null
    var current: LaunchSettingsSnapshot? = null
    var receipt: LaunchSettingsSnapshot? = null
}

private data class PreparedConnection(val connection: NewConnection, val switch: RuntimeSwitch)

private fun LoginSupport?.isCliLogin() = this == LoginSupport.Cli || this == LoginSupport.CliWithDeviceCode

private val ConnectionMethod.hostApp get() = (this as? ConnectionMethod.CliLogin)?.hostApp

private fun EngineManagementState.engine(id: EngineId) = engines.firstOrNull { it.descriptor.id == id }

/** How long the wizard waits for its own management inspection to finish. */
private const val INSPECTION_WAIT_MILLIS = 15_000L

/** How long the wizard waits for the user to finish the sign-in page. */
private const val SIGN_IN_WAIT_MILLIS = 300_000L
