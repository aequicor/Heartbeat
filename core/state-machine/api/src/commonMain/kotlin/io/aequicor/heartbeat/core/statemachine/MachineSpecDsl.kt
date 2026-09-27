package io.aequicor.heartbeat.core.statemachine

import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.reflect.cast

/** DSL scope marker of [machineSpec]. */
@DslMarker
public annotation class MachineDsl

/**
 * Declares a machine: every state via [MachineSpecBuilder.state] (also states without outgoing transitions),
 * transitions via `on<Intent> { goto / stay / effect / output }`.
 *
 * ```
 * public val ChatMachineSpec: MachineSpec<ChatState, ChatIntent, ChatEffect, ChatOutput> =
 *     machineSpec(ChatMachineKey, initial = ChatState.Idle) {
 *         state<ChatState.Idle> {
 *             on<ChatIntent.Public.Open> {
 *                 goto<ChatState.Loading> { ChatState.Loading(intent.chatId) }
 *                 effect { ChatEffect.Load(intent.chatId) }
 *             }
 *         }
 *         state<ChatState.Ready> {
 *             on<ChatIntent.Public.SendPrompt>(guard = { intent.text.isNotBlank() }) {
 *                 goto<ChatState.Generating> { ChatState.Generating(state.chatId) }
 *                 effect { ChatEffect.Generate(intent.text) }
 *             }
 *             on<ChatIntent.Internal.DraftSaved> { stay { state.copy(draft = intent.text) } }
 *         }
 *         state<ChatState.Error>()
 *         any { on<ChatIntent.Public.Reset> { goto<ChatState.Idle> { ChatState.Idle } } }
 *         onEffectFailure { _, error -> ChatIntent.Internal.Failed(error.message.orEmpty()) }
 *     }
 * ```
 *
 * Validation on build: [initial] and every `goto` target are declared, no state is declared twice.
 * States are matched by their exact class: declare leaf classes, not sealed parents.
 */
public fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> machineSpec(
    key: MachineKey<S, I, P, E, O>,
    initial: S,
    block: MachineSpecBuilder<S, I, E, O>.() -> Unit,
): MachineSpec<S, I, E, O> = MachineSpecBuilder<S, I, E, O>().apply(block).build(key, initial)

/** Body of [machineSpec]. */
@MachineDsl
public class MachineSpecBuilder<S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput>
    @PublishedApi
    internal constructor() {
        private val handlers = LinkedHashMap<KClass<out S>, MutableList<Handler<S, I, E, O>>>()
        private val anyHandlers = mutableListOf<Handler<S, I, E, O>>()
        private val descriptors = mutableListOf<TransitionDescriptor<S, I>>()
        private var persistence: Persistence<S, E>? = null
        private var effectFailure: ((E, Throwable) -> I?)? = null

        /** Declares state [T] and its outgoing transitions. Every state the machine can be in must be declared. */
        public inline fun <reified T : S> state(noinline block: StateBuilder<S, T, I, E, O>.() -> Unit = {}): Unit =
            addState(T::class, block)

        /** Transitions valid in every state; a transition of the current state takes precedence. */
        public fun any(block: StateBuilder<S, S, I, E, O>.() -> Unit) {
            StateBuilder<S, S, I, E, O>(this, source = null, cast = { it }).block()
        }

        /**
         * Maps a failed effect to an intent (usually `Internal.Failed`). Without it a failure is only logged.
         * Not called for cancellation.
         */
        public fun onEffectFailure(map: (effect: E, error: Throwable) -> I?) {
            check(effectFailure == null) { "onEffectFailure is already set" }
            effectFailure = map
        }

        /**
         * Saves the current state into the feature scope's saved state, so the machine survives process death.
         *
         * Effects do not survive: a state that waits for an effect result (`Loading`) would wait forever.
         * Map such states in [restore] — relaunch the effect or fall back to a stable state:
         *
         * ```
         * persist(ChatState.serializer()) { saved ->
         *     when (saved) {
         *         is ChatState.Loading -> restore(saved, ChatEffect.Load(saved.chatId)) // relaunch
         *         is ChatState.Generating -> restore(ChatState.Ready(saved.chatId))      // give up
         *         else -> restore(saved)
         *     }
         * }
         * ```
         */
        public fun persist(
            serializer: KSerializer<S>,
            restore: (RestoreScope<S, E>.(saved: S) -> Restoration<S, E>)? = null,
        ) {
            check(persistence == null) { "persist is already set" }
            persistence = Persistence(serializer, restore)
        }

        @PublishedApi
        internal fun <T : S> addState(type: KClass<T>, block: StateBuilder<S, T, I, E, O>.() -> Unit) {
            check(type !in handlers) { "state ${type.label} is declared twice" }
            handlers[type] = mutableListOf()
            StateBuilder(this, source = type, cast = type::cast).block()
        }

        /** Registers the transition built by [create] from its id. */
        internal fun addHandler(create: (id: Int) -> Handler<S, I, E, O>) {
            val handler = create(descriptors.size)
            val source = handler.descriptor.source
            descriptors += handler.descriptor
            if (source == null) anyHandlers += handler else handlers.getValue(source) += handler
        }

        @PublishedApi
        internal fun build(key: MachineKey<S, I, *, E, O>, initial: S): MachineSpec<S, I, E, O> {
            check(initial::class in handlers) {
                "${key.name}: initial state ${initial::class.label} is not declared"
            }
            descriptors.forEach { transition ->
                val target = transition.target ?: return@forEach
                check(target in handlers) {
                    "${key.name}: target of ${transition.describe()} is not declared, add state<${target.label}>()"
                }
            }
            return MachineSpec(
                key = key,
                initial = initial,
                persistence = persistence,
                handlers = handlers.mapValues { (_, list) -> list.toList() },
                anyHandlers = anyHandlers.toList(),
                effectFailure = effectFailure,
            )
        }
    }

/** Transitions out of state [T] (or out of any state for [MachineSpecBuilder.any]). */
@MachineDsl
public class StateBuilder<S : MachineState, T : S, I : MachineIntent, E : MachineEffect, O : MachineOutput>
    internal constructor(
        private val spec: MachineSpecBuilder<S, I, E, O>,
        private val source: KClass<out S>?,
        private val cast: (S) -> T,
    ) {
        /**
         * Declares the reaction to intent [J] (or any of its subtypes). [guard] must be pure; when it is `false`
         * the transition does not apply. Without `goto`/`stay` the intent is accepted and the state is unchanged.
         */
        public inline fun <reified J : I> on(
            noinline guard: (TransitionScope<T, J>.() -> Boolean)? = null,
            noinline block: TransitionBuilder<S, T, I, J, E, O>.() -> Unit = {},
        ): Unit = addTransition(J::class, guard, block)

        @PublishedApi
        internal fun <J : I> addTransition(
            type: KClass<J>,
            guard: (TransitionScope<T, J>.() -> Boolean)?,
            block: TransitionBuilder<S, T, I, J, E, O>.() -> Unit,
        ) {
            val builder = TransitionBuilder<S, T, I, J, E, O>().apply(block)
            val scope: (S, I) -> TransitionScope<T, J> = { state, intent ->
                transitionScope(cast(state), type.cast(intent))
            }
            val next = builder.next
            spec.addHandler { id ->
                Handler(
                    descriptor = TransitionDescriptor(id, source, type, builder.target, isGuarded = guard != null),
                    guard = guard?.let { check -> { state, intent -> scope(state, intent).check() } },
                    next = if (next == null) {
                        { state, _ -> state }
                    } else {
                        { state, intent -> scope(state, intent).next() }
                    },
                    effects = builder.effects.map { create -> { state, intent -> scope(state, intent).create() } },
                    outputs = builder.outputs.map { create -> { state, intent -> scope(state, intent).create() } },
                )
            }
        }
    }

/** Body of `on<J> { }`: at most one of [goto]/[stay], any number of [effect] and [output]. */
@MachineDsl
public class TransitionBuilder<S : MachineState, T : S, I : MachineIntent, J : I, E : MachineEffect, O : MachineOutput>
    internal constructor() {
        internal var target: KClass<out S>? = null
            private set
        internal var next: (TransitionScope<T, J>.() -> S)? = null
            private set
        internal val effects = mutableListOf<TransitionScope<T, J>.() -> E?>()
        internal val outputs = mutableListOf<TransitionScope<T, J>.() -> O?>()

        /** Leaves the current state for a state of class [R] built by [create] (re-entering, if [R] is the same). */
        public inline fun <reified R : S> goto(noinline create: TransitionScope<T, J>.() -> R): Unit =
            setGoto(R::class, create)

        /** Stays in the current state, replacing its data with [update]. Running effects continue. */
        public fun stay(update: TransitionScope<T, J>.() -> T) {
            check(next == null) { "a transition has at most one goto/stay" }
            next = update
        }

        /** Launches the effect built by [create] after the transition; `null` launches nothing. */
        public fun effect(create: TransitionScope<T, J>.() -> E?) {
            effects += create
        }

        /** Emits the output built by [create] after the transition; `null` emits nothing. */
        public fun output(create: TransitionScope<T, J>.() -> O?) {
            outputs += create
        }

        @PublishedApi
        internal fun setGoto(type: KClass<out S>, create: TransitionScope<T, J>.() -> S) {
            check(next == null) { "a transition has at most one goto/stay" }
            target = type
            next = create
        }
    }

/** State and intent available inside transition lambdas. */
@MachineDsl
public interface TransitionScope<out T : MachineState, out J : MachineIntent> {
    /** Current state. */
    public val state: T

    /** Incoming intent. */
    public val intent: J
}

private fun <T : MachineState, J : MachineIntent> transitionScope(state: T, intent: J): TransitionScope<T, J> =
    object : TransitionScope<T, J> {
        override val state: T = state
        override val intent: J = intent
    }
