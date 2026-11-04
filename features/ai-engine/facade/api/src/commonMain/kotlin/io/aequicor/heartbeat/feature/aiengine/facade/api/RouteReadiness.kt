package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cached readiness of one exact engine/binding route. The route is identified without a model: all models of a
 * binding share its credential, source revision and runtime. Reading it never probes an installation, discovers
 * models, opens a runtime or reads storage; observations come only from the owner's cancellable refresh.
 */
public sealed interface RouteAvailability {
    /** The exact route is confirmed for the current binding, source revision and runtime; it may be offered. */
    public data object Available : RouteAvailability

    /** The route is positively unusable now; [reason] is typed and never contains credentials or paths. */
    public data class Unavailable(public val reason: RouteUnavailableReason) : RouteAvailability

    /** Nothing has confirmed the route since it appeared or changed; it is never offered as available. */
    public data object Unknown : RouteAvailability
}

/** Why an exact route stopped being available. */
public enum class RouteUnavailableReason {
    /** The binding was removed or is no longer saved. */
    BindingMissing,

    /** The binding is saved but disabled. */
    BindingDisabled,

    /** The engine registration is absent, for example because its switch is off. */
    EngineMissing,

    /** The engine is unsupported on this host or its installation probe failed. */
    EngineUnavailable,

    /** The credential source of the binding is gone. */
    SourceMissing,

    /** The source revision differs from the one the route was confirmed with. */
    SourceRevisionChanged,

    /** The pooled runtime of the exact engine and source shut itself down. */
    RuntimeClosed,

    /** The last observed launch was refused by engine admission. */
    AdmissionRefused,

    /** The source is present but its login is no longer accepted. */
    AuthenticationFailed,
}

/** Exact engine and binding of a route; a model is chosen within this stable identity. */
public data class RouteKey(val engine: EngineId, val binding: EngineBindingId)

/** Stable route identity of [this] target, independent of its model. */
public fun EngineTarget.routeKey(): RouteKey = RouteKey(engine, binding)

/**
 * Profile-owned readiness of exact routes. [routes] is the cached observation consumers combine with, and
 * [availability] is a pure lookup over it. Neither starts IO, so a model list may be built from them freely.
 *
 * The owner also refreshes routes itself when an already known route re-appears after a binding, source or runtime
 * loss: that refresh is a cancellable profile-owned operation, not part of a read. A caller may request the same
 * operation explicitly with [refresh]; cancelling it leaves the previous readiness untouched.
 */
public interface ExactRouteReadiness {
    /** Cached readiness of every saved binding; changes when a route is lost or restored. */
    public val routes: StateFlow<Map<RouteKey, RouteAvailability>>

    /** Cached readiness of [target]; [RouteAvailability.Unknown] for a route that was never confirmed. */
    public fun availability(target: EngineTarget): RouteAvailability =
        routes.value[target.routeKey()] ?: RouteAvailability.Unknown

    /**
     * Explicitly revalidates the exact route of [target]: probes the engine installation and confirms the current
     * binding and source revision. It never opens an ordinary runtime, discovers models or reads credentials.
     * Suspends only for that explicit work; cancellation leaves the previous readiness unchanged. No delivery
     * fallback, model or default target is substituted.
     */
    public suspend fun refresh(target: EngineTarget): RouteAvailability = RouteAvailability.Unknown

    /**
     * Records a typed failure observed while opening the exact route, without IO. Reasons that do not invalidate
     * the route (busy, transport, cancellation) are ignored.
     */
    public fun recordFailure(target: EngineTarget, failure: EngineFailure): Unit = Unit
}

/** Readiness of a host without the facade implementation: nothing is ever confirmed or refreshed. */
public object UnknownRouteReadiness : ExactRouteReadiness {
    private val none = MutableStateFlow<Map<RouteKey, RouteAvailability>>(emptyMap())

    override val routes: StateFlow<Map<RouteKey, RouteAvailability>> = none.asStateFlow()
}
