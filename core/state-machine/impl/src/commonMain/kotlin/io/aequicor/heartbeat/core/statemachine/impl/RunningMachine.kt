package io.aequicor.heartbeat.core.statemachine.impl

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.Resolution
import io.aequicor.heartbeat.core.statemachine.Restoration
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.TransitionDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.DefaultState
import ru.nsk.kstatemachine.state.IState
import ru.nsk.kstatemachine.state.transition
import ru.nsk.kstatemachine.statemachine.ProcessingResult
import ru.nsk.kstatemachine.statemachine.StateMachine
import ru.nsk.kstatemachine.statemachine.createStdLibStateMachine
import ru.nsk.kstatemachine.statemachine.startBlocking
import ru.nsk.kstatemachine.transition.TransitionType
import kotlin.concurrent.Volatile
import kotlin.reflect.KClass

/**
 * A machine started from a [MachineSpec] in a feature scope.
 *
 * The spec decides (`resolve`), KStateMachine mirrors the decision in its graph (one node per state class, one
 * transition per declaration, guarded by the resolved declaration), this class owns the typed state, outputs and
 * effects. Intents are processed one at a time under a mutex; the engine is created with the std-lib coroutine
 * abstraction, so processing never leaves the caller's coroutine and cannot interleave.
 *
 * Everything is logged under `SM/<name>`: incoming intents, transitions, `stay` updates, ignored intents, effects
 * (start / completion / cancellation / failure), outputs, start, restore and stop.
 */
internal class RunningMachine<S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput>(
    private val spec: MachineSpec<S, I, E, O>,
    private val scope: ScopeHandle,
    private val effects: EffectHandler<E, I>,
) : Machine<S, I, O> {
    private val log = Log.tag("SM/${spec.name}")
    private val mutex = Mutex()
    private val saved: S? = spec.persistence?.let { scope.savedState.consume(savedStateKey(spec.name), it.serializer) }
    private val restoration: Restoration<S, E>? = saved?.let(spec::restore)
    private val mutableState = MutableStateFlow(restoration?.state ?: spec.initial)
    private val mutableOutputs = MutableSharedFlow<O>(extraBufferCapacity = OUTPUT_BUFFER)

    // Engine names must distinguish equal simple names and must never collide with the machine root.
    private val nodes: Map<KClass<out S>, DefaultState> = spec.states.mapIndexed { index, type ->
        type to DefaultState("${spec.name}/state/$index:${type.simpleName ?: "?"}")
    }.toMap()

    private val engine: StateMachine = createEngine(start = mutableState.value)

    /** Parent of all effect coroutines: cancelled once on [stop], so no effect outlives the machine. */
    private val effectsJob: CompletableJob = SupervisorJob(scope.coroutineScope.coroutineContext[Job])

    @Volatile
    private var stateScope: CoroutineScope = newStateScope()

    @Volatile
    private var isRunning = false

    override val name: String get() = spec.name
    override val state: StateFlow<S> = mutableState.asStateFlow()
    override val outputs: Flow<O> = mutableOutputs.asSharedFlow()

    /** Starts the engine in the restored (or initial) state and relaunches restored effects. Main thread, once. */
    fun start() {
        spec.persistence?.let { persistence ->
            scope.savedState.register(savedStateKey(spec.name), persistence.serializer) { mutableState.value }
        }
        engine.startBlocking()
        isRunning = true
        val current = mutableState.value
        log.i { "started in ${current.label()} (${origin()}), scope ${scope.name}" }
        restoration?.effects?.forEach(::launchEffect)
    }

    private fun origin(): String {
        val saved = saved ?: return "initial"
        val restoration = restoration
        val isUnchanged = restoration == null || (restoration.state == saved && restoration.effects.isEmpty())
        return when {
            saved::class !in nodes -> "saved ${saved.label()} is not declared any more, fell back to initial"
            isUnchanged -> "restored from saved state"
            else -> "restored from saved ${saved.label()}, relaunching ${restoration.effects.map { it.label() }}"
        }
    }

    /** Stops processing and cancels running effects. Called when the feature scope closes. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        effectsJob.cancel()
        if (spec.persistence != null) scope.savedState.unregister(savedStateKey(spec.name))
        log.i { "stopped in ${mutableState.value.label()}, scope ${scope.name} closed" }
    }

    override suspend fun send(intent: I): SendResult = dispatch(intent, source = "own feature")

    /** Delivers [intent]; [source] explains in the log who sent it. */
    suspend fun dispatch(intent: I, source: String): SendResult = dispatch(intent, source, originScope = null)

    private suspend fun dispatch(intent: I, source: String, originScope: CoroutineScope?): SendResult = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!isRunning) {
            log.w { "← ${intent.label()} ($source) dropped: machine is not running" }
            return SendResult.NotRunning
        }
        // Check after acquiring the mutex: another intent may have exited the originating state while we waited.
        if (originScope != null && (originScope !== stateScope || !originScope.isActive)) {
            log.w { "← ${intent.label()} ($source) dropped: effect state is no longer active" }
            return SendResult.Ignored
        }
        log.d { "← ${intent.label()} ($source)" }
        val from = mutableState.value
        val resolution = try {
            spec.resolve(from, intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "cannot resolve ${intent.label()} in ${from.label()}: invalid spec or failing spec lambda" }
            throw e
        }
        if (resolution == null) {
            log.w { "ignored ${intent.label()} in ${from.label()}: no transition" }
            return SendResult.Ignored
        }
        val result = engine.processEvent(IntentEvent(resolution.transition))
        if (result != ProcessingResult.PROCESSED) {
            val error = IllegalStateException("${spec.name}: engine did not process ${resolution.transition} ($result)")
            log.e(error) { "graph diverged from the spec" }
            throw error
        }
        apply(resolution, intent)
        SendResult.Accepted
    }

    private fun apply(resolution: Resolution<S, I, E, O>, intent: I) {
        val from = resolution.from
        val to = resolution.to
        when {
            resolution.isStateChange -> {
                stateScope.cancel()
                stateScope = newStateScope()
                log.i { "${from.label()} --${intent.label()}--> ${to.label()}" }
            }

            from != to -> log.d { "${from.label()} ~${intent.label()}~> ${to.label()} (data updated)" }

            else -> log.d { "${from.label()} --${intent.label()}--> (no change)" }
        }
        mutableState.value = to
        resolution.outputs.forEach(::emitOutput)
        resolution.effects.forEach(::launchEffect)
    }

    private fun emitOutput(output: O) {
        val subscribers = mutableOutputs.subscriptionCount.value
        val isDelivered = mutableOutputs.tryEmit(output) && subscribers > 0
        if (isDelivered) {
            log.d { "→ output ${output.label()} (subscribers: $subscribers)" }
        } else {
            log.w { "→ output ${output.label()} dropped (subscribers: $subscribers)" }
        }
    }

    private fun launchEffect(effect: E) {
        val label = effect.label()
        val effectScope = stateScope
        val feedback = object : EffectScope<I> {
            override suspend fun send(intent: I): SendResult =
                dispatch(intent, source = "effect $label", originScope = effectScope)
        }
        // An effect whose own result leaves the state is cancelled after it has finished: not a cancellation.
        var isFinished = false
        val job = effectScope.launch {
            log.d { "effect $label started" }
            try {
                effects.handle(effect, feedback)
                isFinished = true
                log.d { "effect $label completed" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isFinished = true
                log.e(e) { "effect $label failed" }
                reportFailure(effect, label, e, effectScope)
            }
        }
        job.invokeOnCompletion { cause ->
            when {
                isFinished || cause !is CancellationException -> Unit

                // The state is still current, so nobody cancelled the effect: a CancellationException escaped from
                // inside it (withTimeout, a Deferred cancelled elsewhere). Without a result the machine would wait
                // forever — it is a failure.
                effectScope.isActive -> {
                    log.e(cause) { "effect $label failed: cancelled from inside" }
                    effectScope.launch { reportFailure(effect, label, cause, effectScope) }
                }

                else -> log.d { "effect $label cancelled" }
            }
        }
    }

    private suspend fun reportFailure(effect: E, label: String, error: Throwable, effectScope: CoroutineScope) {
        val failure = spec.onEffectFailure(effect, error) ?: return
        dispatch(failure, source = "failure of effect $label", originScope = effectScope)
    }

    /** Coroutines of the current state's effects: cancelled when the state is left or the machine stops. */
    private fun newStateScope(): CoroutineScope =
        CoroutineScope(scope.coroutineScope.coroutineContext + SupervisorJob(effectsJob))

    private fun createEngine(start: S): StateMachine = createStdLibStateMachine(name = spec.name, start = false) {
        logger = StateMachine.Logger { message -> log.v { message() } }
        ignoredEventHandler = StateMachine.IgnoredEventHandler { ignored ->
            log.e { "engine ignored ${ignored.event}: graph diverged from the spec" }
        }
        nodes.values.forEach { addState(it) }
        setInitialState(nodes.getValue(start::class))
        spec.transitions.forEach { descriptor ->
            val owner: IState = descriptor.source?.let(nodes::getValue) ?: this
            val target = descriptor.target?.let(nodes::getValue)
            owner.transition<IntentEvent>(name = "${descriptor.id}: ${descriptor.describe()}") {
                guard = { event.transition === descriptor }
                targetState = target
                // Re-entering the same state must exit it; LOCAL keeps a parent (machine root) active.
                type = if (target === owner) TransitionType.EXTERNAL else TransitionType.LOCAL
            }
        }
    }

    /** Engine event carrying the transition already chosen by the spec. */
    private class IntentEvent(val transition: TransitionDescriptor<*, *>) : Event {
        override fun toString(): String = transition.describe()
    }

    private companion object {
        const val OUTPUT_BUFFER = 64

        fun savedStateKey(name: String) = "machine:$name"
    }
}
