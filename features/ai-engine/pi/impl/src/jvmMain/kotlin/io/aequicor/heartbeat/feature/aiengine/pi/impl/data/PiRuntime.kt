package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal data class PiRuntimeCredentials(
    val identity: RuntimeIdentity,
    val source: AuthSource.ManagedKey,
    val fingerprint: String,
)

internal class PiRuntime(
    private val credentials: PiRuntimeCredentials,
    private val settings: PiSettings,
    private val processes: PiProcessLauncher,
    private val environment: PiSessionEnvironment,
    private val toggles: FeatureToggles,
) : EngineRuntime,
    CreatesSessions {
    override val identity get() = credentials.identity
    private val source get() = credentials.source
    private val credential get() = credentials.fingerprint
    private val profile get() = environment.profile
    private val dispatchers get() = environment.dispatchers
    private val mutex = Mutex()
    private val sessions: MutableSet<PiSession> = ConcurrentHashMap.newKeySet()

    @Volatile var isClosed: Boolean = false
        private set
    override val features: EngineFeatures = PiFeatures(listOf(CreatesSessions to this))

    suspend fun validate() {
        if (isClosed || profile.isClosed) {
            piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
        if (!toggles.get(AiEngines) || !toggles.get(PiEnabled)) {
            piFailure(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        if (settings.source(identity.source) != source || processes.credentialFingerprint(source) != credential) {
            authenticationFailure(AuthFailureReason.SourceChanged, identity.source)
        }
    }

    override suspend fun create(request: CreateSessionRequest): ActiveSession {
        val session = prepare(request)
        // Process startup runs outside the lock so close() and other creations are not blocked by it.
        session.first.start { event, failed -> processes.start(source, session.second, event, failed) }
        // A started process must be registered or shut down even if the caller is cancelled meanwhile.
        withContext(NonCancellable) {
            mutex.withLock {
                if (isClosed) {
                    session.first.shutdown()
                    piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
                }
                sessions += session.first
            }
        }
        return session.first
    }

    private suspend fun prepare(request: CreateSessionRequest): Pair<PiSession, String?> = mutex.withLock {
        validate()
        val configuration = settings.snapshot()
        if (request.target.engine != identity.engine ||
            configuration.bindings[request.target.binding.value] != source
        ) {
            authenticationFailure(AuthFailureReason.AuthMismatch, identity.source)
        }
        if (!request.target.model.value.startsWith(source.scope.provider.value + "/")) {
            piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
        val directory = request.workspace?.let {
            configuration.workspaces[it.value] ?: piFailure(
                EngineFailure.Request(RequestFailureReason.Invalid),
            )
        }
        withContext(dispatchers.main) {
            PiSession(
                request,
                ExecutionRoute(
                    identity.engine,
                    request.target.binding,
                    identity.source,
                    identity.revision,
                    request.workspace,
                ),
                environment,
                ::validate,
                { sessions.remove(it) },
            )
        } to directory
    }

    override suspend fun close() = mutex.withLock {
        isClosed = true
        sessions.toList().forEach { it.shutdown() }
        sessions.clear()
    }
}

internal class PiFeatures(private val entries: List<Pair<EngineFeatureKey<*>, EngineFeature>>) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val entry = entries.firstOrNull { it.first.id == key.id && it.first.type == key.type }?.second
            ?: return FeatureAccess.Unsupported
        // The key's KClass was matched to the declaration and checked against the actual instance.
        @Suppress("UNCHECKED_CAST")
        return if (key.type.isInstance(entry)) FeatureAccess.Available(entry as F) else FeatureAccess.Unsupported
    }
}
