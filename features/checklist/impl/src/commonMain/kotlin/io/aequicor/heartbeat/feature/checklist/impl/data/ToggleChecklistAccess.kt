package io.aequicor.heartbeat.feature.checklist.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.checklist.impl.domain.ChecklistAccess

/** Keeps toggle IO outside the presentation layer. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ToggleChecklistAccess(private val toggles: FeatureToggles) : ChecklistAccess {
    override suspend fun isEnabled(): Boolean = toggles.get(ChecklistEnabled)
}
