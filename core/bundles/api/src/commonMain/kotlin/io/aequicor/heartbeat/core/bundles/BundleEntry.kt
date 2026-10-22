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

    /**
     * Creates the bundle graph. [foundation] is the foundation graph (an implementation of `FoundationAppBindings`);
     * [imports] is an instance of [importsType] (or `Unit` when there is none).
     */
    public fun createGraph(foundation: Any, imports: Any): Any

    /** `true` when the bundle has a per-profile graph; the rest of the profile members apply only then. */
    public val hasProfileGraph: Boolean get() = false

    /** Interface of cross-bundle accessors the profile graph consumes, or `null`. */
    public val profileImportsType: KClass<*>? get() = null

    /** Interfaces implemented by the profile graph whose accessors are published for the profile. */
    public val profileExportTypes: List<KClass<*>> get() = emptyList()

    /**
     * Creates the profile graph from the [appGraph] of this bundle and the [foundation] profile graph (the host's
     * `ProfileGraph`); [imports] is an instance of [profileImportsType] (or `Unit`).
     */
    public fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any =
        error("Bundle $id has no profile graph")
}
