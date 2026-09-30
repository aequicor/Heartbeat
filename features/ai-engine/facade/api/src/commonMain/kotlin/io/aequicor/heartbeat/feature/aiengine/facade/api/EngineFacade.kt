package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import kotlinx.coroutines.flow.StateFlow

/**
 * Profile-owned entry point. All suspend operations are main-safe, throw EngineException for domain failures,
 * and propagate CancellationException unchanged. No API exposes credential material, native clients or processes.
 * Implementations recheck toggles, ownership and route compatibility before open/resume and subsequent turns.
 * Closing the profile invalidates its handles. Reads do not discover credentials or execute helpers implicitly.
 */
public interface EngineFacade {
    /** Known, enabled registrations and their cached installation state. */
    public val engines: EngineCatalog

    /** Profile-persistent engine/source relationships. */
    public val bindings: EngineBindings

    /** Model discovery scoped to an engine and binding. */
    public val models: ModelCatalog

    /** Unified catalog of Heartbeat and externally created sessions. */
    public val sessions: SessionCatalog

    /** Cached account limits on an explicit credential route, independent of active conversations. */
    public val providerUsage: ProviderUsageCatalog
}

/** Cached engine catalog; neither observation nor feature lookup starts a process. */
public interface EngineCatalog {
    /** Disabled registrations are absent; saved bindings are retained. */
    public val state: StateFlow<List<EngineInfo>>

    /** Explicitly probes installation requirements and updates cached observations. */
    public suspend fun refresh(engine: EngineId): EngineInfo

    /** Resolves optional engine-wide APIs, including native session discovery. */
    public fun features(engine: EngineId): EngineFeatures
}

/** Connection configuration, independent of active session handles and authentication UI. */
public interface EngineBindings {
    /** Saved bindings; an expired login does not remove its binding. */
    public val state: StateFlow<List<EngineBinding>>

    /** Creates or updates the same engine/source binding after ownership and compatibility checks. */
    public suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int = 0): EngineBinding

    /** Disabling prevents new executions; a previously accepted turn may finish. */
    public suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean)

    /** Removes only the binding. Active uses must first be released; external credentials remain intact. */
    public suspend fun disconnect(binding: EngineBindingId)

    /** Checks authentication in an engine-derived context; source revision belongs to the returned observation. */
    public suspend fun check(target: EngineTarget, workspace: WorkspaceRef? = null): BindingCheck
}

/** Cached model discovery never implies universal model availability across credential routes. */
public interface ModelCatalog {
    /** Cached models; an empty initial cache is distinguished by its observation metadata. */
    public fun observe(engine: EngineId, binding: EngineBindingId): StateFlow<ModelCatalogSnapshot>

    /** Explicit discovery; failure is surfaced rather than replacing valid cached data with an empty list. */
    public suspend fun refresh(engine: EngineId, binding: EngineBindingId): ModelCatalogSnapshot
}

/** Last model snapshot and its freshness. */
public data class ModelCatalogSnapshot(val models: List<ModelInfo>, val observation: Observation)

/** Unified profile catalog. Source discovery failure is partial coverage, never silent loss of entries. */
public interface SessionCatalog {
    /** Reads a stable indexed page. The query and profile must match the opaque cursor's scope. */
    public suspend fun page(query: SessionQuery = SessionQuery(), request: PageRequest = PageRequest()): SessionPage

    /** Returns a lazy stored-session handle; does not resume the session or generate text. */
    public suspend fun get(ref: SessionRef): EngineSession

    /** Explicitly discovers sessions in all configured matching stores, including native CLI sessions. */
    public suspend fun refresh(query: SessionQuery = SessionQuery()): SessionDiscoveryReport
}

/** Stored session independent of runtime lifetime. History and resumption are separate optional contracts. */
public interface EngineSession {
    /** Cached metadata; changes cause consumers to resolve effective capabilities again. */
    public val summary: StateFlow<SessionSummary>

    /** Includes independently negotiated history, resume, fork and metadata operations. */
    public val features: EngineFeatures
}

/**
 * Handle to execution owned by a profile runtime. It allows one active turn; concurrent sends fail with Busy.
 * Implementations follow ActiveSessionMachineSpec and serialize commands per native session, even across handles.
 * Reading state never starts generation. Capabilities must be resolved again when state/model changes.
 */
public interface ActiveSession {
    /** Persistent native session address. */
    public val ref: SessionRef

    /** Fixed credential and workspace route, with the checked source revision. */
    public val route: ExecutionRoute

    /** Authoritative execution state, including pending requests and the latest completed outcome. */
    public val state: StateFlow<ActiveSessionState>

    /** Optional operations bound to this active handle. */
    public val features: EngineFeatures

    /**
     * Returns after confirmed lease release; failures leave Closing retryable. Never stops a profile-owned turn,
     * deletes history or signs out. The implementation keeps the machine alive until release is confirmed.
     */
    public suspend fun close()
}
