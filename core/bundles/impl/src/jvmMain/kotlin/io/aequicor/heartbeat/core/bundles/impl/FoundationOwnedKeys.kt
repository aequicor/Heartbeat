package io.aequicor.heartbeat.core.bundles.impl

import io.aequicor.heartbeat.core.bundles.BindingKey
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings

/**
 * Keys of the stateful services the foundation owns: every non-set accessor of [FoundationAppBindings] and
 * [FoundationProfileBindings]. A bundle that exported one of them would publish a second instance of the service next
 * to the one the foundation hands to every bundle graph, so [BundleHost] fails such a bundle when it registers its
 * exports. A deliberate exception is made by passing a smaller set to `BundleHost`, never by exporting silently.
 */
internal fun foundationOwnedKeys(): Set<BindingKey> =
    listOf(FoundationAppBindings::class.java, FoundationProfileBindings::class.java)
        .flatMap { accessorsOf(it) }
        .filterNot { it.isSetAccessor() }
        .mapTo(LinkedHashSet()) { bindingKeyOf(it.genericReturnType) }
