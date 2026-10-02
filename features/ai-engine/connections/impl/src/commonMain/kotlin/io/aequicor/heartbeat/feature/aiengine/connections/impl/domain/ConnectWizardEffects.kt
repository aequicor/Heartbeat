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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableEngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.create
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        requireAvailable(effect.engine)
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
        requireAvailable(engine)
        return write(engine, method, credential)
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
