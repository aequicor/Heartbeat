package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import kotlin.time.Duration

/** Mobile platforms have no local processes: command actions are not offered. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedCommandRunner : CommandRunner {
    override val isAvailable: Boolean = false

    override suspend fun run(directory: String, command: String, timeout: Duration): CommandOutcome =
        throw UnsupportedOperationException("Background commands require Desktop")
}
