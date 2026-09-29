package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/** Marker for a typed optional engine operation. Implementations are bound to their engine/session context. */
public interface EngineFeature

/** Typed key; matching ids alone must never permit an unchecked cast to an unrelated feature contract. */
public open class EngineFeatureKey<F : EngineFeature>(public val id: EngineFeatureId, public val type: KClass<F>)

/** Effective capabilities. Resolve again after owner state changes; each operation revalidates its conditions. */
public interface EngineFeatures {
    /** Resolves without IO; distinguishes absent support from temporarily blocked support. */
    public fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F>
}

/** Typed availability, independent of the descriptor's declared potential support. */
public sealed interface FeatureAccess<out F : EngineFeature> {
    /** The operation contract is currently available. */
    public data class Available<F : EngineFeature>(val feature: F) : FeatureAccess<F>

    /** The contract exists but its execution is currently blocked. */
    public data class Unavailable(val reason: EngineFailure) : FeatureAccess<Nothing>

    /** The adapter has no implementation of this contract. */
    public data object Unsupported : FeatureAccess<Nothing>
}

/** Stored transcript access. Implementations normalize native pages/replay without generating a response. */
public interface SessionHistory : EngineFeature {
    /** Loads one chronological window or throws EngineException; a failed read is never an empty page. */
    public suspend fun page(request: HistoryPageRequest = HistoryPageRequest()): HistoryPage

    /**
     * Replays changes after the atomic page checkpoint, then follows updates. Repeated events are allowed.
     * Expired checkpoints, overflow and rewritten history emit HistoryInvalidated and end this subscription.
     * Consumers reload after invalidation; silently dropping events is forbidden.
     */
    public fun watch(after: HistoryCheckpoint): Flow<SessionEvent>

    /** Typed history key. */
    public companion object : EngineFeatureKey<SessionHistory>(
        EngineFeatureId("session.history"),
        SessionHistory::class,
    )
}

/** Resume operation bound to one stored session; authorization is revalidated before runtime attachment. */
public interface ResumesSessions : EngineFeature {
    /** Resolves an explicit route; no fallback to another binding or implicit account change is allowed. */
    public suspend fun resume(request: ResumeSessionRequest): ActiveSession

    /** Typed resume key. */
    public companion object : EngineFeatureKey<ResumesSessions>(
        EngineFeatureId("session.resume"),
        ResumesSessions::class,
    )
}

/** Forks a native conversation, preserving the original and assigning a distinct SessionRef. */
public interface ForksSessions : EngineFeature {
    /** Forks through the given item, or the current end when null; does not start a turn. */
    public suspend fun fork(through: ItemId? = null): SessionRef

    /** Typed fork key. */
    public companion object : EngineFeatureKey<ForksSessions>(EngineFeatureId("session.fork"), ForksSessions::class)
}

/** Changes a stored native title. */
public interface RenamesSessions : EngineFeature {
    /** Sets a user-visible title without resuming or generating. */
    public suspend fun rename(title: String)

    /** Typed rename key. */
    public companion object : EngineFeatureKey<RenamesSessions>(
        EngineFeatureId("session.rename"),
        RenamesSessions::class,
    )
}

/** Archives or restores native history; deletion is intentionally a separate future contract. */
public interface ArchivesSessions : EngineFeature {
    /** Updates native archive status without deleting history. */
    public suspend fun setArchived(archived: Boolean)

    /** Typed archive key. */
    public companion object : EngineFeatureKey<ArchivesSessions>(
        EngineFeatureId("session.archive"),
        ArchivesSessions::class,
    )
}

/** Model changes are allowed between turns, within the same engine and credential binding. */
public interface SwitchesModels : EngineFeature {
    /** Rejects busy sessions or inaccessible models; records the actual model on subsequent turns. */
    public suspend fun switchTo(model: ModelId)

    /** Typed model-switch key. */
    public companion object : EngineFeatureKey<SwitchesModels>(EngineFeatureId("session.model"), SwitchesModels::class)
}

/** Replies to an outstanding engine permission request; historical events never authorize an action. */
public interface RequestsPermissions : EngineFeature {
    /** Validates the turn, request and option against the current pending state. */
    public suspend fun respond(decision: PermissionDecision)

    /** Typed permission key. */
    public companion object : EngineFeatureKey<RequestsPermissions>(
        EngineFeatureId("session.permissions"),
        RequestsPermissions::class,
    )
}

/**
 * Marker: the session applies [PromptRequest.trust] to its tool approvals. Actions the level does not cover still
 * surface through [RequestsPermissions]; such an engine never asks about read-only actions. A file edit is a write
 * or edit inside the session's working directory outside `.git`: writes elsewhere, deletes, moves and commands are
 * not edits. Hook directories configured outside `.git` (`core.hooksPath`) still count as edits.
 * Declared in the descriptor so a consumer can offer the choice before a session exists.
 */
public interface AppliesTrustLevels : EngineFeature {
    /** Typed trust key. */
    public companion object : EngineFeatureKey<AppliesTrustLevels>(
        EngineFeatureId("session.trust"),
        AppliesTrustLevels::class,
    )
}

/** Accepted image media types negotiated for the current model and route. */
public interface AcceptsImages : EngineFeature {
    /** MIME types accepted by this route. */
    public val mediaTypes: Set<String>

    /** Typed image-input key. */
    public companion object : EngineFeatureKey<AcceptsImages>(EngineFeatureId("input.images"), AcceptsImages::class)
}

/** Accepted resource media types negotiated for the current route. */
public interface AcceptsResources : EngineFeature {
    /** MIME types accepted by this route. */
    public val mediaTypes: Set<String>

    /** Typed resource-input key. */
    public companion object : EngineFeatureKey<AcceptsResources>(
        EngineFeatureId("input.resources"),
        AcceptsResources::class,
    )
}

/** Engine-defined operation mode; ids are local to the current route. */
public data class SessionMode(val id: String, val title: String)

/** Selects an engine-defined mode without changing the credential source. */
public interface HasModes : EngineFeature {
    /** Negotiated modes currently offered by the engine. */
    public val modes: List<SessionMode>

    /** Changes mode between turns or fails with a domain error. */
    public suspend fun select(id: String)

    /** Typed mode key. */
    public companion object : EngineFeatureKey<HasModes>(EngineFeatureId("session.modes"), HasModes::class)
}

/** Native discovery capability. Lack of support never means that the native history is empty. */
public interface ListsSessions : EngineFeature {
    /** Lists sessions regardless of whether Heartbeat created them; native default filters must be overridden. */
    public suspend fun page(query: SessionQuery, request: PageRequest): SessionPage

    /** Typed native discovery key. */
    public companion object : EngineFeatureKey<ListsSessions>(EngineFeatureId("engine.sessions"), ListsSessions::class)
}

/** Creates sessions through an explicit engine, model and credential binding. */
public interface CreatesSessions : EngineFeature {
    /** Checks ownership, source revision and effective credentials before creating an idle session. */
    public suspend fun create(request: CreateSessionRequest): ActiveSession

    /** Typed creation key. */
    public companion object : EngineFeatureKey<CreatesSessions>(
        EngineFeatureId("engine.create"),
        CreatesSessions::class,
    )
}

/** Prompt submission bound to one active session. */
public interface SendsPrompts : EngineFeature {
    /**
     * Returns after native acceptance. Ambiguous delivery throws Request.OutcomeUnknown with the original
     * request id. Reconcile before retrying; request ids do not imply native deduplication.
     */
    public suspend fun send(request: PromptRequest): TurnId

    /** Typed submission key. */
    public companion object : EngineFeatureKey<SendsPrompts>(EngineFeatureId("session.send"), SendsPrompts::class)
}

/** Explicit interruption bound to one active session. */
public interface CancelsTurns : EngineFeature {
    /** Completion is established by native state/events, never inferred from coroutine cancellation. */
    public suspend fun cancel(turn: TurnId)

    /** Typed interruption key. */
    public companion object : EngineFeatureKey<CancelsTurns>(EngineFeatureId("session.cancel"), CancelsTurns::class)
}

/** Reconciles an unavailable active handle with the authoritative runtime. */
public interface ReconcilesSession : EngineFeature {
    /** Waits for refreshed state or a domain failure. Never resends prompts or changes credential bindings. */
    public suspend fun synchronize()

    /** Typed recovery key. */
    public companion object : EngineFeatureKey<ReconcilesSession>(
        EngineFeatureId("session.reconcile"),
        ReconcilesSession::class,
    )
}
