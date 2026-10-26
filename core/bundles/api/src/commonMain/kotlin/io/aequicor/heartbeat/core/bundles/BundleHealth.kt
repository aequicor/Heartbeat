package io.aequicor.heartbeat.core.bundles

/** State of a bundle after the host started it; the launcher uses it to decide on rollback. */
public sealed interface BundleHealth {
    /** Graph created and every import has an exporter. */
    public data object Healthy : BundleHealth

    /** Graph created, but imports without an exporter are unavailable. */
    public data class Degraded(public val missing: List<BindingKey>) : BundleHealth

    /** The bundle could not be started or violates the wiring rules. */
    public data class Failed(public val cause: Throwable) : BundleHealth
}

/** Type-based identity of a cross-bundle binding: the normalized generic return type of an accessor. */
public data class BindingKey(public val signature: String) {
    override fun toString(): String = signature
}

/**
 * A binding is requested while the bundle that exports it is failed or absent; [cause] is the failure of the exporter
 * when it broke while producing the binding.
 */
public class BundleUnavailableException(
    public val key: BindingKey,
    public val requestedBy: BundleId?,
    cause: Throwable? = null,
) : IllegalStateException("No healthy bundle exports $key (requested by ${requestedBy ?: "host"})", cause)

/** A cross-bundle binding is requested while it is being resolved: the chain of keys is [path]. */
public class BundleCycleException(public val path: List<BindingKey>) :
    IllegalStateException("Cross-bundle cycle: ${path.joinToString(" -> ")}")
