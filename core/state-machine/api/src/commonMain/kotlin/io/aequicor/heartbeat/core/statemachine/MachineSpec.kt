package io.aequicor.heartbeat.core.statemachine

import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/**
 * Declarative description of a feature machine: every state, every transition, its effects and outputs.
 * Built with [machineSpec] in the feature api module; pure (no IO), so the feature tests it without a runtime
 * (see `assertTransition`). Started by [MachineLauncher].
 */
public class MachineSpec<S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput>
    internal constructor(
        /** The key this spec is registered under. */
        public val key: MachineKey<S, I, *, E, O>,
        /** State of a fresh machine (nothing restored). */
        public val initial: S,
        /** How the machine survives process death, or `null` if it starts from [initial] every time. */
        public val persistence: Persistence<S, E>?,
        private val handlers: Map<KClass<out S>, List<Handler<S, I, E, O>>>,
        private val anyHandlers: List<Handler<S, I, E, O>>,
        private val effectFailure: ((E, Throwable) -> I?)?,
    ) {
        /** Every declared state class, in declaration order. */
        public val states: List<KClass<out S>> = handlers.keys.toList()

        /** Every declared transition: those of each state (in state order), then the `any` ones. */
        public val transitions: List<TransitionDescriptor<S, I>> =
            (handlers.values.flatten() + anyHandlers).map { it.descriptor }

        /** Machine name, equals [MachineKey.name]. */
        public val name: String get() = key.name

        /**
         * Computes what [intent] does in [state]: the matching transition of the state wins over an `any` transition;
         * `null` if nothing matches (the intent is ignored).
         *
         * @throws IllegalStateException if [state] is not declared, two transitions match (guards must be mutually
         * exclusive) or a transition produced a state of the wrong class.
         */
        public fun resolve(state: S, intent: I): Resolution<S, I, E, O>? {
            val own = checkNotNull(handlers[state::class]) {
                "$name: state ${state::class.label} is not declared in the spec"
            }
            val handler = own.single(state, intent) ?: anyHandlers.single(state, intent) ?: return null
            val next = handler.next(state, intent)
            val descriptor = handler.descriptor
            val expected = descriptor.target ?: state::class
            check(next::class == expected) {
                "$name: ${descriptor.describe()} produced ${next::class.label}, expected ${expected.label}"
            }
            return Resolution(
                transition = descriptor,
                from = state,
                to = next,
                effects = handler.effects.mapNotNull { it(state, intent) },
                outputs = handler.outputs.mapNotNull { it(state, intent) },
            )
        }

        /** Intent to send when [effect] failed with [error] (`onEffectFailure`), or `null` to only log the failure. */
        public fun onEffectFailure(effect: E, error: Throwable): I? = effectFailure?.invoke(effect, error)

        /**
         * Where a machine restored in [saved] continues: the `persist` restore mapping, or [saved] as is.
         * A restored state that is not declared (the spec changed since it was saved) falls back to [initial].
         */
        public fun restore(saved: S): Restoration<S, E> {
            if (saved::class !in handlers) return Restoration(initial, emptyList<E>())
            val restoration = persistence?.restore(saved) ?: Restoration(saved, emptyList<E>())
            check(restoration.state::class in handlers) {
                "$name: restore of ${saved::class.label} produced undeclared ${restoration.state::class.label}"
            }
            return restoration
        }

        private fun List<Handler<S, I, E, O>>.single(state: S, intent: I): Handler<S, I, E, O>? {
            val matching = filter { it.matches(state, intent) }
            check(matching.size <= 1) {
                "$name: ${intent::class.label} in ${state::class.label} matches " +
                    matching.joinToString { it.descriptor.describe() } + " — guards must be mutually exclusive"
            }
            return matching.firstOrNull()
        }
    }

/** Static metadata of one declared transition (for the runtime graph, diagrams and logs). */
public class TransitionDescriptor<S : MachineState, I : MachineIntent> internal constructor(
    /** Unique id, in declaration order. */
    public val id: Int,
    /** Source state class; `null` for an `any` transition (valid in every state). */
    public val source: KClass<out S>?,
    /** Intent class the transition reacts to (may be a sealed parent). */
    public val intent: KClass<out I>,
    /** Target state class of a `goto`; `null` for `stay` (data update without leaving the state). */
    public val target: KClass<out S>?,
    /** Whether the transition has a guard. */
    public val isGuarded: Boolean,
) {
    /** `Idle --Open--> Loading`, `* --Reset--> Idle`, `Ready --Draft--> (stay)`. */
    public fun describe(): String = "${source?.label ?: "*"} --${intent.label}--> ${target?.label ?: "(stay)"}"

    override fun toString(): String = describe()
}

/** What a resolved intent does: the new state, effects to launch and outputs to emit. */
public class Resolution<S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput> internal constructor(
    /** The transition that handled the intent. */
    public val transition: TransitionDescriptor<S, I>,
    /** State before the intent. */
    public val from: S,
    /** State after the intent. */
    public val to: S,
    /** Effects to launch, in declaration order. */
    public val effects: List<E>,
    /** Outputs to emit, in declaration order. */
    public val outputs: List<O>,
) {
    /**
     * `true` for a `goto` (also to the same state class): the machine leaves [from] and its running effects are
     * cancelled. `false` for `stay`.
     */
    public val isStateChange: Boolean get() = transition.target != null
}

/** Persistence of a machine across process death: the state serializer and the restore mapping. */
public class Persistence<S : MachineState, E : MachineEffect> internal constructor(
    /** Serializer of the machine state. */
    public val serializer: KSerializer<S>,
    private val mapping: (RestoreScope<S, E>.(saved: S) -> Restoration<S, E>)?,
) {
    /** Applies the restore mapping to [saved]; without a mapping the machine continues in [saved]. */
    internal fun restore(saved: S): Restoration<S, E> =
        mapping?.invoke(RestoreScope(), saved) ?: Restoration(saved, emptyList())
}

/** Where a restored machine continues: [state] plus the [effects] to relaunch (the ones cut by process death). */
@ConsistentCopyVisibility
public data class Restoration<S : MachineState, E : MachineEffect> internal constructor(
    /** State to continue in. */
    public val state: S,
    /** Effects launched right after the restart, in the scope of [state]. */
    public val effects: List<E>,
)

/** Receiver of the `persist` restore mapping. */
@MachineDsl
public class RestoreScope<S : MachineState, E : MachineEffect> internal constructor() {
    /** Continue in [state], relaunching [effects]. */
    public fun restore(state: S, vararg effects: E): Restoration<S, E> = Restoration(state, effects.toList())
}

/** A declared transition with its lambdas; lambdas receive the current state and the incoming intent. */
internal class Handler<S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput>(
    val descriptor: TransitionDescriptor<S, I>,
    private val guard: ((S, I) -> Boolean)?,
    val next: (S, I) -> S,
    val effects: List<(S, I) -> E?>,
    val outputs: List<(S, I) -> O?>,
) {
    /** Whether this transition handles [intent] in [state]. */
    fun matches(state: S, intent: I): Boolean =
        descriptor.intent.isInstance(intent) && (guard == null || guard.invoke(state, intent))
}
