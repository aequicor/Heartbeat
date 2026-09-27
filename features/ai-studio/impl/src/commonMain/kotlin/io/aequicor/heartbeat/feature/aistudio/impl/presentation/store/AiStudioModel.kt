package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** Immutable presentation derived from the feature machine. */
data object AiStudioScreenState : MVIState

/** User events consumed by the screen store. */
sealed interface AiStudioScreenIntent : MVIIntent

/** Reserved contract for one-off screen actions. */
sealed interface AiStudioScreenAction : MVIAction

/** Feature-scoped screen store reflecting the machine and forwarding intents. */
@SingleIn(AiStudioScope::class)
@Inject
class AiStudioModel(
    machine: Machine<AiStudioState, AiStudioIntent, AiStudioOutput>,
    @ForScope(AiStudioScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    val store = factory.create<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>(
        "AiStudio",
        AiStudioScreenState,
        onError = { this },
    ) { reflect(machine) { this } }

    init {
        store.start(scope.coroutineScope)
    }
}
