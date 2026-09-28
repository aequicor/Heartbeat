package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.koogProvider
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
        if (source.info.revision !is AuthRevision.Known) {
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
                    it.reveal { chars -> transport.open(provider, chars.concatToString(), model) }
                }
            }

            is AuthSource.NoAuth -> {
                revalidate(connection)
                transport.open(provider, null, model)
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
