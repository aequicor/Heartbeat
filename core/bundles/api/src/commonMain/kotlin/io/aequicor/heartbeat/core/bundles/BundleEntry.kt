package io.aequicor.heartbeat.core.bundles

import kotlin.reflect.KClass

/** Stable identifier of a bundle (`web`, `engines`, …); also the discovery order. */
public data class BundleId(public val value: String) : Comparable<BundleId> {
    override fun compareTo(other: BundleId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

/**
 * Entry point of a runtime-loaded bundle, discovered through `ServiceLoader`.
 *
 * The bundle owns a statically compiled Metro graph. It reaches other bundles only through [importsType], an
 * interface of unqualified accessors the host backs with a lazy proxy, and publishes its own services through
 * the accessors of [exportTypes], which its graph implements. Feature code never sees this contract.
 */
public interface BundleEntry {
    /** Identifier of the bundle. */
    public val id: BundleId

    /** Interface of cross-bundle accessors this bundle consumes, or `null` when it consumes none. */
    public val importsType: KClass<*>?

    /** Interfaces implemented by the bundle graph whose accessors are published to other bundles. */
    public val exportTypes: List<KClass<*>>

    /** Creates the bundle graph; [imports] is an instance of [importsType] (or `Unit` when there is none). */
    public fun createGraph(imports: Any): Any
}
