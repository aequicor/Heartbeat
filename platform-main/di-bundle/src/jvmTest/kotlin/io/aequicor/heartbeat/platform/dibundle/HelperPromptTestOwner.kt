package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperPromptAttempt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Fresh admission barrier in the actual profile graph; unrelated helpers never select it. */
@ContributesIntoSet(ProfileScope::class)
@ExposeImplBinding
@SingleIn(ProfileScope::class)
@Inject
class HelperPromptTestOwner : ScheduledHelperPromptOwner {
    override val feature: String = "itest"
    val fresh = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private var collections = 0

    override fun admission(attempt: HelperPromptAttempt): Flow<Boolean> = flow {
        if (++collections == 2) {
            fresh.complete(Unit)
            release.await()
        }
        emit(true)
    }
}

/** Identity matches the singleton contributed to the SPI set. */
@ContributesTo(ProfileScope::class)
interface HelperPromptTestAccessors {
    val helperPromptOwner: HelperPromptTestOwner
}
