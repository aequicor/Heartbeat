package io.aequicor.heartbeat.feature.applogs.impl.domain

import io.aequicor.heartbeat.feature.applogs.api.AppLogsOperation
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOutcome

/** Native log actions. Implementations are main-safe and release temporary snapshots on cancellation. */
interface AppLogsActions {
    /** Performs one user-selected operation without exposing log contents to presentation or the machine. */
    suspend fun execute(operation: AppLogsOperation): AppLogsOutcome
}
