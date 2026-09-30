package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebFetch
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

/** Profile services a runtime validates against, launches processes with and publishes next to its sessions. */
internal data class PiRuntimeServices(
    val settings: PiSettings,
    val processes: PiProcessLauncher,
    val workspaces: LocalWorkspaces,
    val nativeWeb: PiNativeWeb,
)

internal class PiRuntime(
    private val credentials: PiRuntimeCredentials,
    private val environment: PiSessionEnvironment,
    private val services: PiRuntimeServices,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions {
    private val log = Log.tag("PiRuntime")
    override val identity get() = credentials.identity
    private val source get() = credentials.source
    private val credential get() = credentials.fingerprint
    private val profile get() = environment.profile
    private val dispatchers get() = environment.dispatchers
    private val settings get() = services.settings
    private val processes get() = services.processes
    private val workspaces get() = services.workspaces
    private val mutex = Mutex()
    private val sessions: MutableSet<PiSession> = ConcurrentHashMap.newKeySet()

    @Volatile var isClosed: Boolean = false
        private set
    override val features: EngineFeatures =
        PiFeatures(listOf(CreatesSessions to this, AttachesSessions to this, NativeWebFetch to services.nativeWeb))

    suspend fun validate() {
        if (isClosed || profile.isClosed) {
            piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
        if (!environment.toggles.get(AiEngines) || !environment.toggles.get(PiEnabled)) {
            piFailure(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        if (settings.source(identity.source) != source || processes.credentialFingerprint(source) != credential) {
            authenticationFailure(AuthFailureReason.SourceChanged, identity.source)
        }
    }

    override suspend fun create(request: CreateSessionRequest): ActiveSession = launch(request, null)

    /**
     * Restarts Pi on the stored transcript of [ref] in this profile, e.g. after an application restart.
     * A transcript still served by a live process of this runtime is never opened twice.
     */
    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession {
        if (ref.engine != identity.engine || ref.source != PiSessionSource) {
            piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        val file = processes.transcript(ref.nativeId)
            ?: piFailure(EngineFailure.Session(SessionFailureReason.NotFound))
        log.i { "Attaching stored Pi session" }
        return launch(CreateSessionRequest(request.target, request.workspace), PiTranscript(ref, file))
    }

    private suspend fun launch(request: CreateSessionRequest, transcript: PiTranscript?): ActiveSession {
        if (transcript != null && mutex.withLock { isServed(transcript.ref) }) busy()
        val session = prepare(request)
        // Process startup runs outside the lock so close() and other creations are not blocked by it.
        session.first.start(
            { event, failed -> processes.start(source, session.second, event, failed) },
            transcript,
        )
        // A started process must be registered or shut down even if the caller is cancelled meanwhile.
        withContext(NonCancellable) {
            mutex.withLock {
                if (isClosed) {
                    session.first.shutdown()
                    piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
                }
                if (transcript != null && isServed(transcript.ref)) {
                    session.first.shutdown()
                    busy()
                }
                sessions += session.first
            }
        }
        return session.first
    }

    /** A transcript is served by at most one live Pi process; a detached one keeps it until its turn settles. */
    private fun isServed(ref: SessionRef): Boolean = sessions.any { it.attachedRef == ref }

    private fun busy(): Nothing {
        log.w { "Stored Pi session is still served by a running process" }
        piFailure(EngineFailure.Session(SessionFailureReason.Busy))
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
        val directory = resolvePiWorkspace(request.workspace, workspaces, configuration.workspaces)
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

/** Resolves a native process working directory, retaining explicitly configured legacy routes. */
internal suspend fun resolvePiWorkspace(
    ref: WorkspaceRef?,
    workspaces: LocalWorkspaces,
    configured: Map<String, String>,
): String? = ref?.let {
    workspaces.resolve(it) ?: configured[it.value] ?: piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
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
