package io.aequicor.heartbeat.feature.checklist.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistOutput
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState

internal typealias ChecklistMachine = Machine<ChecklistState, ChecklistIntent, ChecklistOutput>

/** Whether user actions are accepted in the active profile. */
internal interface ChecklistAccess {
    suspend fun isEnabled(): Boolean
}
