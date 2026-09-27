package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlinx.serialization.Serializable

// ---- a feature machine: contract (would be in features/<x>/api) + wiring (features/<x>/impl) ----

object CounterMachineKey : MachineKey<CounterState, CounterIntent, CounterIntent.Public, CounterEffect, CounterOutput> {
    override val name = "counter"
}

@Serializable
sealed interface CounterState : MachineState {
    @Serializable
    data object Idle : CounterState

    @Serializable
    data class Counting(val count: Int, val echoed: Int = 0) : CounterState
}

sealed interface CounterIntent : MachineIntent {
    sealed interface Public : CounterIntent {
        data object Increment : Public
    }

    data class Echoed(val count: Int) : CounterIntent
}

sealed interface CounterEffect : MachineEffect {
    data class Echo(val count: Int) : CounterEffect
}

sealed interface CounterOutput : MachineOutput

val CounterMachineSpec = machineSpec(CounterMachineKey, initial = CounterState.Idle) {
    state<CounterState.Idle> {
        on<CounterIntent.Public.Increment> {
            goto<CounterState.Counting> { CounterState.Counting(1) }
            effect { CounterEffect.Echo(1) }
        }
    }
    state<CounterState.Counting> {
        on<CounterIntent.Public.Increment> {
            stay { state.copy(count = state.count + 1) }
            effect { CounterEffect.Echo(state.count + 1) }
        }
        on<CounterIntent.Echoed> { stay { state.copy(echoed = intent.count) } }
    }
    persist(CounterState.serializer())
}

@ContributesTo(TestFeatureScope::class)
@BindingContainer
object CounterMachineBindings {
    @Provides
    @SingleIn(TestFeatureScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(TestFeatureScope::class) scope: ScopeHandle,
        effects: EffectHandler<CounterEffect, CounterIntent>,
    ): Machine<CounterState, CounterIntent, CounterOutput> = launcher.launch(CounterMachineSpec, scope, effects)
}

@ContributesBinding(TestFeatureScope::class)
@Inject
class CounterEffects : EffectHandler<CounterEffect, CounterIntent> {
    override suspend fun handle(effect: CounterEffect, machine: EffectScope<CounterIntent>) {
        when (effect) {
            is CounterEffect.Echo -> machine.send(CounterIntent.Echoed(effect.count))
        }
    }
}
