package io.aequicor.heartbeat.feature.checklist.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEvents
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Paused checklists retain their event wait; the durable outbox replays completion when enabled again. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ChecklistWakeOwner(private val toggles: FeatureToggles) : ScheduledWakeOwner {
    override val feature: String = ChecklistEvents.OWNER
    override fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission> = toggles.observe(ChecklistEnabled).map {
        if (it) ScheduledWakeAdmission.Allow else ScheduledWakeAdmission.Defer
    }
}
