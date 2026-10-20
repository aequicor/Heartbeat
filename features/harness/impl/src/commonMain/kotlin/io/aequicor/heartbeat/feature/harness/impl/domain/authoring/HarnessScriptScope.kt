package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessItem

/**
 * An enabled script runs its hooks, events and tools with access to every chat where the harness applies: wider
 * membership spreads that access like a native On switch, and narrower membership drops its Deny/Ask hooks like
 * a restriction.
 */
internal val Harness.hasEnabledScript: Boolean get() = items.any { it is HarnessItem.Script && it.isEnabled }
