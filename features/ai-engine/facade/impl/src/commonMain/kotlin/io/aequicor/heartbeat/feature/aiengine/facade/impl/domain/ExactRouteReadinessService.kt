package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExactRouteReadiness
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteUnavailableReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.routeKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ExactRouteReadiness] over the profile's cached catalogs and runtime pool. Readiness is derived only from
 * observed state; the single IO is [refresh], which probes the engine installation and never starts a runtime.
 * An owner refresh is serialized, cancellable and commits its result only after the probe and a second check of
 * the exact route, so cancellation or a concurrent removal leaves the previous readiness untouched. A refusal
 * recorded while a probe is in flight is merged at commit and keeps the route unavailable: a concurrent refresh
 * never erases it. Local state is changed only by atomic updates and published from the latest snapshot, so a
 * stale availability snapshot cannot overwrite a newer refusal either.
 *
 * Losing the binding, source revision, source or a pooled runtime invalidates the confirmation. The route becomes
 * [RouteAvailability.Unknown] (or a typed [RouteAvailability.Unavailable]) and is never offered as available;
 * re-appearing inputs are confirmed by one owner refresh, not by a read and not by falling back to another route.
 */
internal class ExactRouteReadinessService(
    private val bindings: StateFlow<List<EngineBinding>>,
    private val sources: StateFlow<List<AuthSource>>,
    private val engines: StateFlow<List<EngineInfo>>,
    private val runtimes: StateFlow<List<RuntimeEntry>>,
    private val context: FacadeContext,
    private val probeEngine: suspend (EngineId) -> Unit,
    private val isAutoRefreshEnabled: Boolean = true,
) : ExactRouteReadiness {
    private val log = Log.tag("RouteReadiness")
    private val local = MutableStateFlow(LocalState())
    private val published = MutableStateFlow<Map<RouteKey, RouteAvailability>>(emptyMap())
    private val refreshLock = Mutex()
    private val jobsLock = Mutex()
    private val jobs = mutableMapOf<RouteKey, Job>()

    override val routes: StateFlow<Map<RouteKey, RouteAvailability>> = published.asStateFlow()

    init {
        context.scope.launch {
            combine(bindings, sources, engines, runtimes) { saved, auth, catalog, pooled ->
                Inputs(saved, auth, catalog, pooled)
            }.collect { inputs -> observe(inputs) }
        }
    }

    override fun availability(target: EngineTarget): RouteAvailability = availability(target.routeKey(), inputs())

    override suspend fun refresh(target: EngineTarget): RouteAvailability = refreshRoute(target.routeKey())

    override fun recordFailure(target: EngineTarget, failure: EngineFailure) {
        val reason = failure.readinessReason() ?: return
        val key = target.routeKey()
        mutate { current ->
            current.copy(
                refused = current.refused + (key to Refusal(reason, current.epoch)),
                attempted = current.attempted - key,
            )
        }
        log.v { "route readiness refused reason=$reason" }
        publish()
    }

    private suspend fun observe(inputs: Inputs) {
        val current = local.value
        val invalid = invalidated(current, inputs)
        val lost = (current.attempted.keys + current.refused.keys).filterTo(mutableSetOf()) {
            !inputs.isResolvable(it)
        }
        if (invalid.isNotEmpty() || lost.isNotEmpty()) {
            mutate { state ->
                state.copy(
                    invalidated = state.invalidated + invalid,
                    attempted = state.attempted - invalid - lost,
                    refused = state.refused - invalid - lost,
                )
            }
            log.v { "route readiness invalidated count=${invalid.size} lost=${lost.size}" }
        }
        publish()
        if (isAutoRefreshEnabled) schedule(inputs)
    }

    /**
     * Confirmed routes whose binding, source or runtime disappeared, whose source revision changed, or that no
     * longer resolve. They are marked stale so a returned route is refreshed instead of silently re-offered.
     */
    private fun invalidated(current: LocalState, inputs: Inputs): Set<RouteKey> = current.confirmed.keys.mapNotNullTo(
        mutableSetOf(),
    ) { key ->
        val binding = inputs.binding(key)
        val source = inputs.source(binding)
        val runtimeClosed = inputs.runtimes.any {
            it.engine == key.engine && it.source == source?.info?.id && it.isClosed
        }
        val revisionChanged = source != null && current.confirmed[key]?.matches(source) == false
        key.takeIf { binding == null || source == null || runtimeClosed || revisionChanged }
    }

    /** Confirms routes that are resolvable but never confirmed, for example after a restore or at startup. */
    private suspend fun schedule(inputs: Inputs) {
        inputs.bindings.forEach { binding ->
            if (!binding.isEnabled) return@forEach
            val key = RouteKey(binding.engine, binding.id)
            val current = local.value
            if (key in current.refused || availability(key, inputs) is RouteAvailability.Available) return@forEach
            if (!inputs.canProbe(key, binding)) return@forEach
            val signature = inputs.signature(key) ?: return@forEach
            if (current.attempted[key] == signature) return@forEach
            mutate { state -> state.copy(attempted = state.attempted + (key to signature)) }
            log.v { "route readiness refresh scheduled engine=${key.engine.value}" }
            launchAutoRefresh(key)
        }
    }

    private suspend fun launchAutoRefresh(key: RouteKey) {
        val job = jobsLock.withLock {
            if (jobs[key]?.isActive == true) return
            context.scope.launch(start = CoroutineStart.LAZY) {
                try {
                    refreshRoute(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Route readiness refresh failed engine=${key.engine.value}" }
                } finally {
                    jobsLock.withLock { jobs.remove(key) }
                }
            }.also { jobs[key] = it }
        }
        // Start outside the lock: the job may run undispatched and remove itself before this call returns.
        job.start()
    }

    private suspend fun refreshRoute(key: RouteKey): RouteAvailability = refreshLock.withLock {
        val before = inputs()
        routeReason(key, before)?.let { reason ->
            publish()
            return@withLock RouteAvailability.Unavailable(reason)
        }
        // Refusals known when the probe starts are the ones a successful probe may clear.
        val started = local.value
        try {
            probeEngine(key.engine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Route readiness probe failed engine=${key.engine.value}" }
            publish()
            return@withLock availability(key, inputs())
        }
        // A concurrent removal while the probe ran is not confirmed: the previous state stays authoritative.
        currentCoroutineContext().ensureActive()
        val after = inputs()
        routeReason(key, after)?.let { reason ->
            publish()
            return@withLock RouteAvailability.Unavailable(reason)
        }
        val engine = after.engine(key.engine) ?: return@withLock RouteAvailability.Unavailable(
            RouteUnavailableReason.EngineMissing,
        )
        val source = after.source(after.binding(key))
        if (engine.availability != EngineAvailability.Available || source == null) {
            publish()
            return@withLock availability(key, after)
        }
        commitConfirmation(key, source, started)
        log.i { "route readiness confirmed engine=${key.engine.value}" }
        publish()
        availability(key, after)
    }

    /** Commits a successful probe without erasing a refusal recorded while that probe was in flight. */
    private fun commitConfirmation(key: RouteKey, source: AuthSource, started: LocalState) {
        val confirmation = Confirmation(source.info.id, source.info.revision)
        mutate { current ->
            // A refusal recorded while the probe ran is newer than the probe: keep it instead of publishing Available.
            val refusal = current.refused[key]
            val keptRefusals = if (refusal != null && refusal == started.refused[key]) {
                current.refused - key
            } else {
                current.refused
            }
            current.copy(
                confirmed = current.confirmed + (key to confirmation),
                invalidated = current.invalidated - key,
                refused = keptRefusals,
                attempted = current.attempted - key,
            )
        }
    }

    /** Structural reasons of the exact route; engine installation readiness is reported separately. */
    private fun routeReason(key: RouteKey, inputs: Inputs): RouteUnavailableReason? {
        val binding = inputs.binding(key) ?: return RouteUnavailableReason.BindingMissing
        if (!binding.isEnabled) return RouteUnavailableReason.BindingDisabled
        inputs.engine(key.engine) ?: return RouteUnavailableReason.EngineMissing
        val source = inputs.source(binding) ?: return RouteUnavailableReason.SourceMissing
        val isRuntimeClosed = inputs.runtimes.any {
            it.engine == key.engine && it.source == source.info.id && it.isClosed
        }
        return RouteUnavailableReason.RuntimeClosed.takeIf { isRuntimeClosed }
    }

    private fun blockingReason(key: RouteKey, inputs: Inputs): RouteUnavailableReason? =
        routeReason(key, inputs) ?: when (inputs.engine(key.engine)?.availability) {
            EngineAvailability.UnsupportedPlatform,
            is EngineAvailability.Unavailable,
            -> RouteUnavailableReason.EngineUnavailable

            EngineAvailability.Available,
            EngineAvailability.Unknown,
            null,
            -> null
        }

    private fun availability(key: RouteKey, inputs: Inputs): RouteAvailability {
        val reason = blockingReason(key, inputs)
        return if (reason == null) confirmedAvailability(key, inputs) else RouteAvailability.Unavailable(reason)
    }

    /** Only a positive confirmation of the current revision and runtime makes a route available. */
    private fun confirmedAvailability(key: RouteKey, inputs: Inputs): RouteAvailability {
        val binding = inputs.binding(key) ?: return RouteAvailability.Unknown
        val source = inputs.source(binding) ?: return RouteAvailability.Unknown
        val engine = inputs.engine(key.engine) ?: return RouteAvailability.Unknown
        val state = local.value
        val confirmed = state.confirmed[key]
        val refusal = state.refused[key]
        return when {
            refusal != null -> RouteAvailability.Unavailable(refusal.reason)
            engine.availability != EngineAvailability.Available -> RouteAvailability.Unknown
            confirmed == null -> RouteAvailability.Unknown
            !confirmed.matches(source) -> RouteAvailability.Unavailable(RouteUnavailableReason.SourceRevisionChanged)
            key in state.invalidated -> RouteAvailability.Unknown
            else -> RouteAvailability.Available
        }
    }

    /**
     * Publishes readiness derived from the latest local snapshot. Every local change is an atomic update that
     * advances [LocalState.epoch]; republishing after a racing update prevents an older snapshot from remaining
     * visible after a newer refusal or confirmation.
     */
    private fun publish() {
        while (true) {
            val snapshot = local.value
            val inputs = inputs()
            val next = inputs.bindings.associate { binding ->
                val key = RouteKey(binding.engine, binding.id)
                key to availability(key, inputs)
            }
            if (next != published.value) {
                published.value = next
            }
            if (local.value.epoch == snapshot.epoch) {
                val available = next.values.count { it == RouteAvailability.Available }
                log.v { "route readiness published available=$available" }
                return
            }
        }
    }

    /** Atomically applies [transform] to the latest local state and advances its epoch. */
    private fun mutate(transform: (LocalState) -> LocalState) {
        local.update { current ->
            val next = transform(current).copy(epoch = current.epoch + 1)
            log.v { "route readiness state epoch=${current.epoch}->${next.epoch} refused=${next.refused.size}" }
            next
        }
    }

    private fun inputs(): Inputs = Inputs(bindings.value, sources.value, engines.value, runtimes.value)

    private data class Confirmation(val source: AuthSourceId, val revision: AuthRevision) {
        fun matches(source: AuthSource): Boolean = this.source == source.info.id && revision == source.info.revision
    }

    /** One recorded refusal; [epoch] distinguishes a refusal that survived a refresh from the cleared one. */
    private data class Refusal(val reason: RouteUnavailableReason, val epoch: Long)

    private data class LocalState(
        val confirmed: Map<RouteKey, Confirmation> = emptyMap(),
        val invalidated: Set<RouteKey> = emptySet(),
        val refused: Map<RouteKey, Refusal> = emptyMap(),
        val attempted: Map<RouteKey, Signature> = emptyMap(),
        val epoch: Long = 0,
    )

    private data class Signature(
        val source: AuthSourceId,
        val revision: AuthRevision,
        val availability: EngineAvailability,
    )

    private class Inputs(
        val bindings: List<EngineBinding>,
        val sources: List<AuthSource>,
        val engines: List<EngineInfo>,
        val runtimes: List<RuntimeEntry>,
    ) {
        fun binding(key: RouteKey): EngineBinding? =
            bindings.firstOrNull { it.id == key.binding && it.engine == key.engine }

        fun source(binding: EngineBinding?): AuthSource? =
            binding?.let { saved -> sources.firstOrNull { it.info.id == saved.authSource } }

        fun engine(id: EngineId): EngineInfo? = engines.firstOrNull { it.descriptor.id == id }

        /** The exact route still has a saved binding, a source and no closed runtime. */
        fun isResolvable(key: RouteKey): Boolean {
            val binding = binding(key) ?: return false
            val source = source(binding) ?: return false
            return runtimes.none { it.engine == key.engine && it.source == source.info.id && it.isClosed }
        }

        fun canProbe(key: RouteKey, binding: EngineBinding): Boolean {
            val status = engine(key.engine)?.availability ?: return false
            if (status == EngineAvailability.UnsupportedPlatform || status is EngineAvailability.Unavailable) {
                return false
            }
            val source = source(binding) ?: return false
            return runtimes.none { it.engine == key.engine && it.source == source.info.id && it.isClosed }
        }

        fun signature(key: RouteKey): Signature? {
            val binding = binding(key) ?: return null
            val source = source(binding) ?: return null
            val status = engine(key.engine)?.availability ?: return null
            return Signature(source.info.id, source.info.revision, status)
        }
    }
}

/** Typed failures that make an exact route unavailable; transient busy, transport and unknown errors are ignored. */
internal fun EngineFailure.readinessReason(): RouteUnavailableReason? = when (this) {
    is EngineFailure.Engine -> when (reason) {
        EngineFailureReason.Incompatible,
        EngineFailureReason.CompatibilityUnknown,
        EngineFailureReason.UnsupportedCapability,
        -> RouteUnavailableReason.AdmissionRefused

        EngineFailureReason.Unavailable,
        EngineFailureReason.RequirementsNotMet,
        EngineFailureReason.Crashed,
        -> RouteUnavailableReason.EngineUnavailable
    }

    is EngineFailure.Authentication -> RouteUnavailableReason.AuthenticationFailed

    is EngineFailure.Session -> when (reason) {
        SessionFailureReason.Changed -> RouteUnavailableReason.SourceRevisionChanged

        SessionFailureReason.Busy,
        SessionFailureReason.NotFound,
        SessionFailureReason.NotResumable,
        -> null
    }

    is EngineFailure.Access -> when (reason) {
        AccessFailureReason.OperationNotAllowed -> RouteUnavailableReason.BindingDisabled
        AccessFailureReason.ModelAccessDenied -> RouteUnavailableReason.AdmissionRefused
    }

    is EngineFailure.History,
    is EngineFailure.Request,
    is EngineFailure.Transport,
    is EngineFailure.Lifecycle,
    is EngineFailure.Unknown,
    is EngineFailure.RateLimited,
    is EngineFailure.QuotaExceeded,
    is EngineFailure.ContextLimitExceeded,
    -> null
}
