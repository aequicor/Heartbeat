package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsResources
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
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
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiActiveSession
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiSessions
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
    val processes: PiProcesses,
    val workspaces: LocalWorkspaces,
    val nativeWeb: NativeWebFetch,
)

internal class PiRuntime(
    private val credentials: PiRuntimeCredentials,
    private val environment: PiSessionEnvironment,
    private val services: PiRuntimeServices,
) : EngineRuntime,
    PiSessions {
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

    // Reserved before transcript lookup and process startup; only accessed under mutex.
    private val attaching = mutableSetOf<SessionRef>()

    @Volatile override var isClosed: Boolean = false
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

    override suspend fun create(request: CreateSessionRequest): PiActiveSession = launch(request, null)

    /**
     * Restarts Pi on the stored transcript of [ref] in this profile, e.g. after an application restart.
     * Reserves the ref before any transcript IO, so concurrent attachments cannot start a second process.
     *
     * Pi transcripts and [SessionRef] carry no credential binding. The caller explicitly chooses the target:
     * its binding must resolve to this runtime's exact source, revision and current credential fingerprint
     * through [prepare] and [validate]. Another binding of that same source is allowed; no fallback binding
     * or credentials are selected from the transcript. Foreign sources must use their own validated runtime.
     */
    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): PiActiveSession {
        if (ref.engine != identity.engine || ref.source != PiSessionSource ||
            request.target.engine != identity.engine
        ) {
            piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
        }
        mutex.withLock {
            validate()
            validateTarget(request.target, settings.snapshot())
            if (isServed(ref) || !attaching.add(ref)) busy()
        }
        try {
            val file = processes.transcript(ref.nativeId)
                ?: piFailure(EngineFailure.Session(SessionFailureReason.NotFound))
            log.i { "Attaching stored Pi session" }
            val launchRequest = CreateSessionRequest(request.target, request.workspace, request.areDetachedToolsEnabled)
            return launch(launchRequest, PiTranscript(ref, file))
        } finally {
            withContext(NonCancellable) { mutex.withLock { attaching.remove(ref) } }
        }
    }

    private suspend fun launch(request: CreateSessionRequest, transcript: PiTranscript?): PiActiveSession {
        val session = prepare(request)
        var isRegistered = false
        try {
            val hosted = session.first.prepareHostedTools()
            // Startup stays outside the lock so close() and unrelated creations can proceed.
            session.first.start(
                { event, failed -> processes.start(source, session.second, event, failed, hosted) },
                transcript,
            )
            // A started process must be registered or shut down even when its caller is cancelled.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (isClosed) piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
                    sessions += session.first
                    isRegistered = true
                }
            }
            return session.first
        } finally {
            if (!isRegistered) withContext(NonCancellable) { session.first.shutdown() }
        }
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
        validateTarget(request.target, configuration)
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

    private fun validateTarget(target: EngineTarget, configuration: PiConfiguration) {
        if (target.engine != identity.engine || configuration.bindings[target.binding.value] != source) {
            authenticationFailure(AuthFailureReason.AuthMismatch, identity.source)
        }
        if (!target.model.value.startsWith(source.scope.provider.value + "/")) {
            piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
        }
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
        if (entry.hasNoSupportedInput()) return FeatureAccess.Unsupported
        // The key's KClass was matched to the declaration and checked against the actual instance.
        @Suppress("UNCHECKED_CAST")
        return if (key.type.isInstance(entry)) FeatureAccess.Available(entry as F) else FeatureAccess.Unsupported
    }
}

private fun EngineFeature.hasNoSupportedInput(): Boolean = when (this) {
    is AcceptsImages -> mediaTypes.isEmpty()
    is AcceptsResources -> mediaTypes.isEmpty()
    else -> false
}
