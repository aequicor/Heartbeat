package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionCheck
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.api.NewConnection
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableEngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.create
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Engine-side services of the connection screens; [management] is off unless engine management is enabled. */
data class EngineServices(
    val facade: EngineFacade,
    val sources: AuthSources,
    val management: EngineManagement = UnavailableEngineManagement(),
)

/** Executes wizard effects. Typed keys are closed after use whatever the outcome. */
class ConnectWizardEffects(private val services: EngineServices, private val selections: ModelSelections) :
    EffectHandler<ConnectWizardEffect, ConnectWizardIntent> {
    private val log = Log.tag("ConnectWizardEffects")

    override suspend fun handle(effect: ConnectWizardEffect, machine: EffectScope<ConnectWizardIntent>) {
        when (effect) {
            ConnectWizardEffect.ObserveEngines -> coroutineScope {
                // Observation never probes, so the wizard checks every engine once to show real availability.
                launch { probeEngines() }
                services.facade.engines.state.collect {
                    machine.send(ConnectWizardIntent.Internal.EnginesChanged(it))
                }
            }

            is ConnectWizardEffect.Connect -> {
                val connection = try {
                    connect(effect.engine, effect.method, effect.credential)
                } finally {
                    (effect.credential as? CredentialInput.ApiKey)?.key?.close()
                }
                deliver(connection, machine)
            }

            is ConnectWizardEffect.CheckConnection -> {
                val models = try {
                    check(effect)
                } finally {
                    (effect.credential as? CredentialInput.ApiKey)?.key?.close()
                }
                machine.send(ConnectWizardIntent.Internal.ConnectionChecked(ConnectionCheck.Succeeded(models)))
            }

            is ConnectWizardEffect.DiscoverModels -> {
                log.i { "discover models engine=${effect.engine.value}" }
                val snapshot = services.facade.models.refresh(effect.engine, effect.binding)
                log.d { "discovered models count=${snapshot.models.size}" }
                machine.send(ConnectWizardIntent.Internal.ModelsLoaded(snapshot.models))
            }

            is ConnectWizardEffect.SaveModels -> {
                selections.update { it.withEnabled(effect.binding, effect.models) }
                log.i { "saved models count=${effect.models.size}" }
                machine.send(ConnectWizardIntent.Internal.Saved)
            }

            is ConnectWizardEffect.Rollback -> {
                // A compensation must finish even if the wizard closes meanwhile.
                withContext(NonCancellable) { rollback(effect.connection) }
                machine.send(ConnectWizardIntent.Internal.RolledBack)
            }
        }
    }

    private suspend fun probeEngines() {
        services.facade.engines.state.value.forEach { info ->
            val engine = info.descriptor.id
            try {
                services.facade.engines.refresh(engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                log.w(e) { "engine probe failed engine=${engine.value}" }
            }
        }
    }

    /**
     * Discovers models through a temporary connection that is removed whatever the outcome. The wizard may be left
     * mid-check, so writing the connection is not cancellable: what was written is always known and removed.
     * The installation probe before it stays cancellable.
     */
    private suspend fun check(effect: ConnectWizardEffect.CheckConnection): Int {
        log.i { "check connection engine=${effect.engine.value} method=${effect.method.id.value}" }
        // A check never changes the engine's runtime, but a host application method still needs the app's runtime.
        if (effect.method.hostApp != null) requireHostRuntime(effect.engine) else requireAvailable(effect.engine)
        signInIfNeeded(effect.engine, effect.method, mayChangeRuntime = false)
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
    ): NewConnection {
        log.i { "connect engine=${engine.value} method=${method.id.value}" }
        val hostApp = method.hostApp
        // The engine's current runtime may be missing (no CLI yet); the app's runtime replaces it once connected.
        if (hostApp != null) requireHostRuntime(engine) else requireAvailable(engine)
        val switched = signInIfNeeded(engine, method, mayChangeRuntime = true)
        var isWritten = false
        val connection = try {
            write(engine, method, credential).also { isWritten = true }
        } finally {
            // The runtime was switched only to sign in; without a connection it is not the user's choice.
            if (!isWritten) switched?.let { withContext(NonCancellable) { restoreSettings(engine, it) } }
        }
        if (hostApp != null) {
            var isApplied = false
            try {
                preferHostRuntime(engine)
                isApplied = true
            } finally {
                if (!isApplied) withContext(NonCancellable) { rollback(connection) }
            }
        }
        return connection
    }

    /**
     * A host application connection needs engine management, which saves the runtime preference, and a runtime of
     * the application to run. The installation is inspected first; management publishes the inspection
     * asynchronously, so the wizard waits until the state shows it (bounded) before judging the app.
     */
    private suspend fun requireHostRuntime(engine: EngineId) {
        val management = services.management
        if (!management.state.value.isEnabled) {
            log.w { "host application connection needs engine management engine=${engine.value}" }
            throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        if (inspect(engine)?.hostApp?.hasRuntime != true) {
            log.w { "host application runtime not found engine=${engine.value}" }
            throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
    }

    /** Inspects the engine and waits (bounded) for management to publish that inspection; the last state otherwise. */
    private suspend fun inspect(engine: EngineId): ManagedEngine? {
        val management = services.management
        val before = management.state.value.engine(engine)?.installation?.checkedAt
        log.i { "inspect engine=${engine.value}" }
        management.execute(engine, EngineCommand.Inspect)
        val inspected = withTimeoutOrNull(INSPECTION_WAIT_MILLIS) {
            management.state.first { state ->
                val managed = state.engine(engine)
                managed != null && managed.installation.checkedAt != before &&
                    (managed.spec.hostApp == null || managed.hostApp != null)
            }
        } ?: management.state.value
        return inspected.engine(engine)
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
    ): LaunchSettings? {
        if (method !is ConnectionMethod.CliLogin || !services.management.state.value.isEnabled) return null
        if (services.management.state.value.engine(engine)?.spec?.login.isCliLogin() != true) return null
        var switched: LaunchSettings? = null
        try {
            var managed = inspect(engine)
            if (method.hostApp != null && !managed.isOnHostRuntime()) {
                if (!mayChangeRuntime) {
                    log.i { "sign-in skipped, a check keeps the runtime engine=${engine.value}" }
                    return null
                }
                val previous = managed?.launch?.settings ?: LaunchSettings()
                preferHostRuntime(engine)
                switched = previous
                managed = inspect(engine)
            }
            if (managed?.login == LoginState.SignedOut) signIn(engine, managed.job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "sign-in not possible engine=${engine.value}" }
        }
        return switched
    }

    private suspend fun restoreSettings(engine: EngineId, settings: LaunchSettings) {
        attempt("restore the launch settings changed for the sign-in") {
            services.management.execute(engine, EngineCommand.Configure(settings))
        }
    }

    private fun ManagedEngine?.isOnHostRuntime(): Boolean {
        val settings = this?.launch?.settings ?: return false
        return settings.runtime == RuntimePreference.HostApp && settings.executable.isNullOrBlank()
    }

    private suspend fun signIn(engine: EngineId, previous: EngineJob?) {
        val management = services.management
        log.i { "sign in engine=${engine.value}" }
        management.execute(engine, EngineCommand.Start(EngineAction.Login()))
        var phase: JobPhase? = null
        try {
            phase = withTimeoutOrNull(SIGN_IN_WAIT_MILLIS) {
                management.state.map { it.engine(engine)?.job }
                    .first { it != null && it != previous && it.action is EngineAction.Login && it.phase.isFinished }
                    ?.phase
            }
        } finally {
            if (phase == null) cancelSignIn(engine)
        }
        when (phase) {
            JobPhase.Succeeded -> log.i { "signed in engine=${engine.value}" }

            null -> log.w { "sign in timed out engine=${engine.value}" }

            else -> log.w {
                "sign in did not complete engine=${engine.value} phase=${phase::class.simpleName} " +
                    "failure=${(phase as? JobPhase.Failed)?.failure?.let { it::class.simpleName }}"
            }
        }
        // The outcome is the wizard's own; the panel must not show it as a login the user started.
        withContext(NonCancellable) {
            attempt("dismiss the sign-in outcome") { management.execute(engine, EngineCommand.Dismiss) }
        }
    }

    private suspend fun cancelSignIn(engine: EngineId) = withContext(NonCancellable) {
        attempt("cancel the sign-in job") { services.management.execute(engine, EngineCommand.Cancel) }
    }

    /**
     * Saves the host application runtime as the engine's runtime instead of a custom executable; a pinned version
     * and the other settings stay. The runtime is a setting of the whole engine, so every connection of the engine
     * runs on it from now on (the wizard says so); removing the connection does not restore the previous choice.
     */
    private suspend fun preferHostRuntime(engine: EngineId) {
        val settings = services.management.state.value.engine(engine)?.launch?.settings ?: LaunchSettings()
        if (settings.runtime == RuntimePreference.HostApp && settings.executable.isNullOrBlank()) return
        services.management.execute(
            engine,
            EngineCommand.Configure(settings.copy(executable = null, runtime = RuntimePreference.HostApp)),
        )
        log.i {
            "host application runtime preferred engine=${engine.value} previous=${settings.runtime} " +
                "replacedExecutable=${!settings.executable.isNullOrBlank()}"
        }
    }

    private suspend fun requireAvailable(engine: EngineId) {
        services.facade.engines.refresh(engine).availability.failure()?.let { throw EngineException(it) }
    }

    /** Creates the source and binds it; the source is forgotten again if binding fails. */
    private suspend fun write(engine: EngineId, method: ConnectionMethod, credential: CredentialInput): NewConnection {
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

    /**
     * Hands the new connection to the wizard. If the wizard is gone (closed while connecting) or no longer waits
     * for it, nobody could roll it back later, so the connection is removed right away.
     */
    private suspend fun deliver(connection: NewConnection, machine: EffectScope<ConnectWizardIntent>) {
        var isDelivered = false
        try {
            isDelivered = machine.send(ConnectWizardIntent.Internal.Connected(connection)) == SendResult.Accepted
        } finally {
            if (!isDelivered) {
                log.w { "connection not accepted by the wizard, rolling it back" }
                withContext(NonCancellable) { rollback(connection) }
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

private fun LoginSupport?.isCliLogin() = this == LoginSupport.Cli || this == LoginSupport.CliWithDeviceCode

private val ConnectionMethod.hostApp get() = (this as? ConnectionMethod.CliLogin)?.hostApp

private fun EngineManagementState.engine(id: EngineId) = engines.firstOrNull { it.descriptor.id == id }

/** How long the wizard waits for engine management to publish the inspection it asked for. */
private const val INSPECTION_WAIT_MILLIS = 15_000L

/** How long the wizard waits for the user to finish the sign-in page. */
private const val SIGN_IN_WAIT_MILLIS = 300_000L
