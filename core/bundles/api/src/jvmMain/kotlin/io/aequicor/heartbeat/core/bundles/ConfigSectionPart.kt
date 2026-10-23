@file:Suppress("FeatureLayerDependency") // names the contract type of a set that a bundle owns

package io.aequicor.heartbeat.core.bundles

import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection

/**
 * A part of the project configuration tree that a bundle other than its owner (`agent-runtime`) contributes. A
 * holder rather than a `Set<ConfigSection>` accessor: the owner exports the whole set, and a bundle never imports a
 * set that it also exports, so the part travels under a key of its own.
 */
public interface ConfigSectionPart {
    /** Sections of the contributing bundle's features. */
    public val configSections: Set<ConfigSection>
}
