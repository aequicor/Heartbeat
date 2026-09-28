package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Inject
internal class KoogAccess(
    private val connections: KoogConnections,
    private val secrets: SecretStore,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val transport: KoogTransport,
) {
    private val log = Log.tag("KoogAccess")

    /**
     * Saves the route of [binding] unless the same connection is already stored; returns whether it changed.
     * Called on every route resolution, so an unchanged route performs no write.
     */
    suspend fun configure(binding: EngineBindingId, source: AuthSource): Boolean {
        checkEnabled()
        val connection = KoogConnection(EngineBinding(binding, KoogEngineId, source.info.id), source)
        if (connections.list().any { it == connection }) return false
        try {
            connections.put(connection)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Rejected Koog route: incompatible source metadata" }
            fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
        return true
    }

    /** Removes the route of [binding]; returns whether a stored route existed. */
    suspend fun remove(binding: EngineBindingId): Boolean {
        if (connections.list().none { it.binding.id == binding }) return false
        connections.remove(binding)
        return true
    }

    /** Whether search tools may be offered to models (toggle `search.engine_tools`). */
    suspend fun searchToolsEnabled(): Boolean = toggles.get(SearchEngineTools)

    suspend fun checkEnabled() {
        if (profile.isClosed) fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        if (!toggles.get(KoogEngineEnabled)) fail(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        if (profile.isClosed) fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }

    suspend fun route(binding: EngineBindingId, identity: RuntimeIdentity? = null): KoogConnection {
        checkEnabled()
        val connection = connections.list().firstOrNull { it.binding.id == binding }
            ?: fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceUnavailable)))
        if (!connection.binding.isEnabled) fail(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        val source = connection.source
        if (koogProvider(source) == null) {
            fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
        // A keyless local endpoint (Ollama) has no credential whose rotation a revision could track, so its
        // authenticator never assigns a known revision; the identity check below still pins the source id.
        if (source !is AuthSource.NoAuth && source.info.revision !is AuthRevision.Known) {
            fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceChanged)))
        }
        val actual = RuntimeIdentity(connection.binding.engine, source.info.id, source.info.revision)
        if (identity != null && actual != identity) {
            fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceChanged)))
        }
        checkEnabled()
        return connection
    }

    suspend fun source(identity: RuntimeIdentity): KoogConnection {
        checkEnabled()
        val connection = connections.list().firstOrNull {
            it.binding.engine == identity.engine && it.source.info.id == identity.source && it.binding.isEnabled
        } ?: fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceUnavailable)))
        return route(connection.binding.id, identity)
    }

    suspend fun validate(connection: KoogConnection) {
        val source = connection.source
        if (source is AuthSource.ManagedKey) read(source).close()
        checkEnabled()
    }

    private suspend fun read(source: AuthSource.ManagedKey): Secret {
        if (!source.secret.value.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) {
            fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceUnavailable)))
        }
        return secrets.read(SecretKey(source.secret.value))
            ?: fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.NotAuthenticated)))
    }

    suspend fun open(connection: KoogConnection, model: String? = null): KoogClient {
        val provider = koogProvider(connection.source)
            ?: fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        return when (val source = connection.source) {
            is AuthSource.ManagedKey -> {
                val secret = read(source)
                secret.use {
                    revalidate(connection)
                    it.reveal { chars ->
                        transport.open(
                            provider,
                            chars.concatToString(),
                            model,
                            source.scope.origin,
                            source.scope.basePath,
                        )
                    }
                }
            }

            is AuthSource.NoAuth -> {
                revalidate(connection)
                transport.open(provider, null, model, source.scope.origin, source.scope.basePath)
            }

            else -> fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
    }

    private suspend fun revalidate(connection: KoogConnection) {
        currentCoroutineContext().ensureActive()
        if (route(connection.binding.id) != connection) {
            fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceChanged)))
        }
    }
}
