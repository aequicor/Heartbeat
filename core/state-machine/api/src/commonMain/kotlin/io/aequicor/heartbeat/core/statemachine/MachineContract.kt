package io.aequicor.heartbeat.core.statemachine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Marker of a machine state. States are immutable values (`data object` / `data class`) of one sealed hierarchy. */
public interface MachineState

/**
 * Marker of an intent processed by a machine. A feature splits its intents into a `Public` sub-hierarchy
 * (sent by other features through [MachineRegistry]) and an `Internal` one (sent by the feature itself and by
 * its effects).
 */
public interface MachineIntent

/** Marker of a side-effect command. Declared by a transition, executed by the feature's [EffectHandler]. */
public interface MachineEffect

/** Marker of a one-shot event emitted to subscribers of a machine (screen store, other features). */
public interface MachineOutput

/**
 * Stable address of a feature machine, declared as an `object` in the feature api module:
 *
 * ```
 * public object ChatMachineKey : MachineKey<ChatState, ChatIntent, ChatIntent.Public, ChatEffect, ChatOutput> {
 *     override val name: String = "chat"
 * }
 * ```
 *
 * [S] — all states, [I] — all intents, [P] — public intents (the only ones other features may send),
 * [E] — effects, [O] — outputs.
 */
public interface MachineKey<S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> {
    /** Unique machine name; used as the log tag `SM/<name>` and as the registry key. */
    public val name: String
}

/**
 * A running machine as other features see it: typed state, outputs and public intents only.
 * Contravariant in intents, so a [Machine] accepting all intents is a ref for the public ones.
 */
public interface MachineRef<out S : MachineState, in P : MachineIntent, out O : MachineOutput> {
    /** Machine name, equals [MachineKey.name]. */
    public val name: String

    /** Current state; updated on every transition and every `stay` update. */
    public val state: StateFlow<S>

    /** One-shot outputs. Hot, without replay: collect before they are emitted. */
    public val outputs: Flow<O>

    /** Delivers [intent]; returns once it is processed (transition done, effects launched). */
    public suspend fun send(intent: P): SendResult
}

/** A running machine inside its own feature: accepts internal intents too. Created by [MachineLauncher]. */
public interface Machine<S : MachineState, I : MachineIntent, O : MachineOutput> : MachineRef<S, I, O>

/** Result of delivering an intent. */
public enum class SendResult {
    /** A transition (or a `stay` update) handled the intent. */
    Accepted,

    /** The current state has no transition for the intent (or its guard rejected it). Logged as WARN. */
    Ignored,

    /** The machine is not running: its feature scope is not open or already closed. Logged as WARN. */
    NotRunning,
}
