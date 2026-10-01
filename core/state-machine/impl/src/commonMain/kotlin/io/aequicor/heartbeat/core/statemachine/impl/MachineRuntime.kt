package io.aequicor.heartbeat.core.statemachine.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Starts machines in feature scopes and keeps the running ones addressable by [MachineKey].
 * One class for both contracts: launching registers the machine, closing its scope unregisters it.
 *
 * Launch and scope close happen on the main thread (like the scopes themselves); lookups and sends may come from
 * any thread (e.g. an effect on the IO dispatcher), so the slot table is an atomically updated immutable map.
 * Other features get a [RegistryRef]: public intents only, every send logged.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<MachineLauncher>())
@ContributesBinding(AppScope::class, binding = binding<MachineRegistry>())
@Inject
internal class MachineRuntime :
    MachineLauncher,
    MachineRegistry {
    private val slots = MutableStateFlow<Map<String, Slot>>(emptyMap())

    override fun <S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput> launch(
        spec: MachineSpec<S, I, E, O>,
        scope: ScopeHandle,
        effects: EffectHandler<E, I>,
    ): Machine<S, I, O> {
        check(!scope.isClosed) { "${spec.name}: cannot launch in closed scope ${scope.name}" }
        val slot = slot(spec.key) // fails on a name clash before anything is started
        val machine = RunningMachine(spec, scope, effects)
        machine.start()
        val ref = RegistryRef(machine)
        scope.onClose {
            machine.stop()
            val instances = slot.remove(ref)
            Log.tag("SM/${spec.name}").v { "unregistered (instances: $instances)" }
        }
        val instances = slot.push(ref)
        val log = Log.tag("SM/${spec.name}")
        if (instances > 1) log.w { "$instances instances running; the latest one is addressed" }
        log.v { "registered (instances: $instances)" }
        return machine
    }

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = observe(key).value

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> {
        // Safe: slot(key) guarantees the slot belongs to this very key object, and only machines built from
        // a spec of that key are registered in it.
        @Suppress("UNCHECKED_CAST")
        return slot(key).current.asStateFlow() as StateFlow<MachineRef<S, P, O>?>
    }

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult {
        val machine = find(key)
        if (machine == null) {
            Log.tag("SM/${key.name}").w { "send ${intent.label()} → ${key.name}: machine is not running" }
            return SendResult.NotRunning
        }
        return machine.send(intent)
    }

    private fun slot(key: MachineKey<*, *, *, *, *>): Slot {
        val slot = slots.value[key.name]
            ?: slots.updateAndGet { if (key.name in it) it else it + (key.name to Slot(key)) }.getValue(key.name)
        check(slot.key === key) { "two machine keys are named '${key.name}': ${slot.key} and $key" }
        return slot
    }

    /** Running instances of one key; the latest one is [current]. Mutated on the main thread only. */
    private class Slot(val key: MachineKey<*, *, *, *, *>) {
        private val instances = MutableStateFlow<List<RegistryRef<*, *, *>>>(emptyList())
        val current = MutableStateFlow<RegistryRef<*, *, *>?>(null)

        /** Adds [ref] as the addressed instance; returns the number of instances. */
        fun push(ref: RegistryRef<*, *, *>): Int =
            instances.updateAndGet { it + ref }.also { current.value = it.last() }.size

        /** Removes [ref]; the previous instance becomes addressed. Returns the number of instances. */
        fun remove(ref: RegistryRef<*, *, *>): Int =
            instances.updateAndGet { it - ref }.also { current.value = it.lastOrNull() }.size
    }
}

/** A running machine as other features see it: sends are logged and marked as coming from another feature. */
private class RegistryRef<S : MachineState, I : MachineIntent, O : MachineOutput>(
    private val machine: RunningMachine<S, I, *, O>,
) : MachineRef<S, I, O> {
    private val log = Log.tag("SM/${machine.name}")
    override val name: String get() = machine.name
    override val state: StateFlow<S> get() = machine.state
    override val outputs: Flow<O> get() = machine.outputs

    override suspend fun send(intent: I): SendResult {
        log.i { "send ${intent.label()} → $name" }
        return machine.dispatch(intent, source = "other feature")
    }
}
