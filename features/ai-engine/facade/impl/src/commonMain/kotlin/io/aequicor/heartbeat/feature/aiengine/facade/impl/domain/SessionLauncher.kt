package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTimes
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Creates and resumes sessions through explicit routes and the pooled runtime. Heartbeat provenance and the last
 * route are recorded in the index; if the handle cannot be opened, the native handle is released.
 */
class SessionLauncher(
    private val routes: RouteResolver,
    private val pool: RuntimePool,
    private val sessions: SessionCatalogService,
    private val host: ActiveSessionHost,
    private val context: FacadeContext,
) {
    private val log = Log.tag("SessionLauncher")

    /** Creates an idle session on [request]'s exact target. */
    suspend fun create(request: CreateSessionRequest): ActiveSession {
        val target = request.target
        log.i { "create session engine=${target.engine.value} binding=${target.binding.value}" }
        val resolved = routes.resolve(target.engine, target.binding, request.workspace, target.model)
        val creator = pool.runtime(resolved).features.resolve(CreatesSessions).orFail()
        val native = adapterCall(log, "create") { creator.create(request) }
        return open(native, resolved.route, target.model) {
            if (native.ref.engine != target.engine) fail(EngineFailure.Unknown())
            val now = context.clock.now()
            record(
                SessionSummary(
                    native.ref,
                    workspace = request.workspace,
                    origin = SessionOrigin.Heartbeat,
                    times = SessionTimes(now, now),
                    lastRoute = resolved.route,
                ),
            )
        }
    }

    /** Attaches the stored session [ref] on [request]'s exact target; never switches credentials implicitly. */
    suspend fun resume(ref: SessionRef, request: ResumeSessionRequest): ActiveSession {
        val target = request.target
        log.i { "resume session engine=${ref.engine.value} binding=${target.binding.value}" }
        if (target.engine != ref.engine) fail(InvalidRequest)
        val resolved = routes.resolve(target.engine, target.binding, request.workspace, target.model)
        val attacher = pool.runtime(resolved).features.resolve(AttachesSessions).orFail()
        val native = adapterCall(log, "attach") { attacher.attach(ref, request) }
        return open(native, resolved.route, target.model) {
            if (native.ref != ref) fail(EngineFailure.Unknown())
            val known = sessions.indexed(ref) ?: SessionSummary(ref)
            record(known.copy(workspace = request.workspace ?: known.workspace, lastRoute = resolved.route))
        }
    }

    /** Resolves a read route without creating or attaching a native session. */
    suspend fun trees(root: SessionRef, access: SessionTreeAccess): SessionTrees {
        val target = access.target
        if (root.engine != target.engine) fail(InvalidRequest)
        val resolved = routes.resolve(target.engine, target.binding, access.workspace, target.model)
        return pool.runtime(resolved).features.resolve(SessionTrees).orFail()
    }

    private suspend fun open(
        native: ActiveSession,
        route: ExecutionRoute,
        model: ModelId,
        prepare: suspend () -> Unit,
    ): ActiveSession {
        var isOpened = false
        try {
            prepare()
            return host.open(native, route, model).also { isOpened = true }
        } finally {
            if (!isOpened) {
                log.w { "handle not opened, releasing native handle engine=${route.engine.value}" }
                withContext(NonCancellable) { release(native) }
            }
        }
    }

    /** Index failures lose only provenance, never an already created native session. */
    private suspend fun record(summary: SessionSummary) {
        try {
            sessions.record(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "session provenance not recorded engine=${summary.ref.engine.value}" }
        }
    }

    private suspend fun release(native: ActiveSession) {
        try {
            native.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "native handle release failed" }
        }
    }
}

/** Engine-wide and stored-session capabilities implemented by the facade. */
class FacadeCapabilities(private val sessions: SessionCatalog, private val launcher: Lazy<SessionLauncher>) {
    /** Native discovery and, when declared, session creation for [registration]. */
    fun engine(registration: EngineRegistration): EngineFeatures {
        val engine = registration.descriptor.id
        val entries = mutableMapOf(ListsSessions.id to available(EngineSessionListing(engine, sessions)))
        if (CreatesSessions.id in registration.descriptor.declaredFeatures) {
            entries[CreatesSessions.id] = available(
                object : CreatesSessions {
                    override suspend fun create(request: CreateSessionRequest): ActiveSession {
                        if (request.target.engine != engine) fail(InvalidRequest)
                        return launcher.value.create(request)
                    }
                },
            )
        }
        if (SessionTrees.id in registration.descriptor.declaredFeatures) {
            entries[SessionTrees.id] = available(object : SessionTrees {
                override fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = flow {
                    if (root.engine != engine) fail(InvalidRequest)
                    emitAll(launcher.value.trees(root, access).observe(root, access))
                }

                override suspend fun history(
                    root: SessionRef,
                    ref: SessionRef,
                    access: SessionTreeAccess,
                ): SessionHistory {
                    if (root.engine != engine || ref.engine != engine || ref.source != root.source) fail(InvalidRequest)
                    return launcher.value.trees(root, access).history(root, ref, access)
                }
            })
        }
        return FeatureTable(entries)
    }

    /** [stored] with facade resumption when the engine declares runtime attachment. */
    fun stored(registration: EngineRegistration, stored: EngineSession): EngineSession {
        // The facade resumes only through runtime attachment; a declared native ResumesSessions alone is not enough.
        val isResumable = AttachesSessions.id in registration.descriptor.declaredFeatures
        return StoredSession(stored) {
            if (isResumable) {
                FeatureAccess.Available(
                    object : ResumesSessions {
                        override suspend fun resume(request: ResumeSessionRequest): ActiveSession =
                            launcher.value.resume(stored.summary.value.ref, request)
                    },
                )
            } else {
                FeatureAccess.Unsupported
            }
        }
    }
}

/** The profile's facade. */
class ProfileEngineFacade(
    override val engines: EngineCatalog,
    override val bindings: EngineBindings,
    override val models: ModelCatalog,
    override val sessions: SessionCatalog,
    override val providerUsage: ProviderUsageCatalog,
) : EngineFacade
