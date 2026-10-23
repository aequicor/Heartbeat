package io.aequicor.heartbeat.core.bundles

/** Typed lookup of services exported by started bundles: the only cross-bundle channel. */
public interface BundleRegistry {
    /** Health of every started bundle by id. */
    public val health: Map<BundleId, BundleHealth>

    /** Resolves the single exporter of [key]; throws [BundleUnavailableException] when none is healthy. */
    public fun resolve(key: BindingKey): Any

    /** Union of the sets exported under [key] by every bundle except [excluding]. */
    public fun collect(key: BindingKey, excluding: BundleId? = null): Set<Any>
}
