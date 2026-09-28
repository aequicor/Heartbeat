package io.aequicor.heartbeat.feature.welcome.impl.presentation.store

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.welcome.api.WelcomeDestination
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent
import io.aequicor.heartbeat.feature.welcome.api.WelcomeOutput
import io.aequicor.heartbeat.feature.welcome.api.WelcomeState
import io.aequicor.heartbeat.feature.welcome.impl.di.scope.WelcomeScope
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

/** Presentation phase derived from the welcome machine. */
enum class WelcomePhase { Preparing, Intro, Ready, Leaving }

/** Immutable presentation derived from the feature machine. */
@Immutable
data class WelcomeScreenState(val phase: WelcomePhase = WelcomePhase.Preparing) : MVIState

/** User events consumed by the screen store. */
enum class WelcomeScreenIntent : MVIIntent { Skip, IntroFinished, OpenStudio, OpenToggles }

/** Reserved contract for one-off screen actions. */
sealed interface WelcomeScreenAction : MVIAction

/** Feature-scoped screen store reflecting the machine and forwarding intents. */
@SingleIn(WelcomeScope::class)
@Inject
class WelcomeModel(
    machine: Machine<WelcomeState, WelcomeIntent, WelcomeOutput>,
    @ForScope(WelcomeScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    val store = factory.create<WelcomeScreenState, WelcomeScreenIntent, WelcomeScreenAction>(
        name = "Welcome",
        initial = WelcomeScreenState().reflectState(machine.state.value),
        onError = {
            scope.coroutineScope.launch { machine.send(WelcomeIntent.Public.Skip) }
            this
        },
    ) {
        reflect(machine) { reflectState(it) }
        reduce { sendTo(machine, it.toMachineIntent()) }
    }

    init {
        store.start(scope.coroutineScope)
        scope.coroutineScope.launch { machine.send(WelcomeIntent.Public.Start) }
    }
}

internal fun WelcomeScreenIntent.toMachineIntent(): WelcomeIntent = when (this) {
    WelcomeScreenIntent.Skip -> WelcomeIntent.Public.Skip
    WelcomeScreenIntent.IntroFinished -> WelcomeIntent.Internal.Finished
    WelcomeScreenIntent.OpenStudio -> WelcomeIntent.Public.Open(WelcomeDestination.Studio)
    WelcomeScreenIntent.OpenToggles -> WelcomeIntent.Public.Open(WelcomeDestination.Toggles)
}

private fun WelcomeScreenState.reflectState(state: WelcomeState): WelcomeScreenState = copy(
    phase = when (state) {
        WelcomeState.Idle, WelcomeState.Checking -> WelcomePhase.Preparing
        WelcomeState.Intro -> WelcomePhase.Intro
        WelcomeState.Ready -> WelcomePhase.Ready
        is WelcomeState.Opening, WelcomeState.OpeningProfile, WelcomeState.Away -> WelcomePhase.Leaving
    },
)
