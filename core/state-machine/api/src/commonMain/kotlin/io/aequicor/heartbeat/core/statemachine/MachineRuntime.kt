package io.aequicor.heartbeat.core.statemachine

import io.aequicor.heartbeat.core.di.ScopeHandle
import kotlinx.coroutines.flow.StateFlow

/**
 * Executes the effects of one machine. Implemented in the feature impl module; results come back to the machine
 * as intents through [EffectScope.send] (any number of times — e.g. for streaming).
 *
 * The effect runs in the scope of the state that launched it: when the machine leaves that state (a `goto`,
 * including re-entering the same state), the effect is cancelled and its later results are dropped.
 * A thrown exception is logged and mapped by `onEffectFailure` of the spec.
 */
public fun interface EffectHandler<in E : MachineEffect, out I : MachineIntent> {
    /** Performs [effect]; main-safe, switch the dispatcher for IO/CPU work. */
    public suspend fun handle(effect: E, machine: EffectScope<I>)

    /** Handler for machines without effects. */
    public companion object {
        /** Fails on any effect: use only with specs that declare none. */
        public val None: EffectHandler<MachineEffect, Nothing> = EffectHandler { effect, _ ->
            error("unexpected effect ${effect::class.label}: machine has no handler")
        }
    }
}

/** Feedback channel of an effect into its machine. */
public interface EffectScope<in I : MachineIntent> {
    /** Sends the result of the effect back to the machine (usually an internal intent). */
    public suspend fun send(intent: I): SendResult
}

/**
 * Starts machines. A machine lives in the scope of its feature: it is created by the feature graph and stops
 * (and leaves [MachineRegistry]) when that scope closes. The provider is lazy: inject the machine into the feature's
 * root component, so it starts together with the feature.
 *
 * ```
 * @ContributesTo(ChatScope::class)
 * @BindingContainer
 * public object ChatMachineBindings {
 *     @Provides @SingleIn(ChatScope::class)
 *     public fun machine(
 *         launcher: MachineLauncher,
 *         @ForScope(ChatScope::class) scope: ScopeHandle,
 *         effects: EffectHandler<ChatEffect, ChatIntent>, // internal impl with @ContributesBinding(ChatScope::class)
 *     ): Machine<ChatState, ChatIntent, ChatOutput> = launcher.launch(ChatMachineSpec, scope, effects)
 * }
 * ```
 */
public interface MachineLauncher {
    /**
     * Starts the machine described by [spec] in [scope] and registers it in [MachineRegistry]. The state is
     * restored from `scope.savedState` when the spec is persistent. Main thread only.
     */
    public fun <S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput> launch(
        spec: MachineSpec<S, I, E, O>,
        scope: ScopeHandle,
        effects: EffectHandler<E, I>,
    ): Machine<S, I, O>
}

/**
 * Running machines of the app, addressed by [MachineKey]. A machine is present while its feature scope is open;
 * if several instances of a feature are open (e.g. two chats in the back stack), the latest one is addressed.
 * Safe to call from any thread. Every send is logged under `SM/<name>`.
 */
public interface MachineRegistry {
    /** The running machine for [key], or `null`. */
    public fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>?

    /** The running machine for [key] over time: `null` while its feature is closed. */
    public fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?>

    /** Sends a public [intent] to the machine for [key]; [SendResult.NotRunning] if there is none. */
    public suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult
}
