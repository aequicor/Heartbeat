package io.aequicor.heartbeat.core.mvi

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.StoreBuilder
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.recover

/** Creates stores with injected dispatchers and content-free lifecycle/event logging. */
@Inject
public class HeartbeatStoreFactory(private val dispatchers: DispatcherProvider) {
    /** Every screen supplies a recoverable UI state; cancellation is never converted into an error. */
    public fun <S : MVIState, I : MVIIntent, A : MVIAction> create(
        name: String,
        initial: S,
        onError: S.(Exception) -> S,
        block: StoreBuilder<S, I, A>.() -> Unit,
    ): Store<S, I, A> = store(initial) {
        configureHeartbeat(name, onError)
        block()
    }

    /** Same as [create], plus the store's latest state readable synchronously for the screen's first frame. */
    public fun <S : MVIState, I : MVIIntent, A : MVIAction> createScreen(
        name: String,
        initial: S,
        onError: S.(Exception) -> S,
        block: StoreBuilder<S, I, A>.() -> Unit,
    ): ScreenStore<S, I, A> {
        val current = MutableStateFlow(initial)
        val store = store(initial) {
            configureHeartbeat(name, onError)
            block()
            // Last in the chain, so it records the state other plugins settled on.
            install {
                this.name = "heartbeat-current"
                onState { _, new ->
                    current.value = new
                    new
                }
            }
        }
        return ScreenStore(store, current.asStateFlow())
    }

    private fun <S : MVIState, I : MVIIntent, A : MVIAction> StoreBuilder<S, I, A>.configureHeartbeat(
        name: String,
        onError: S.(Exception) -> S,
    ) {
        val log = Log.tag("MVI/$name")
        configure {
            this.name = name
            coroutineContext = dispatchers.main
            parallelIntents = false
            // Internal FlowMVI diagnostics can contain state data; our plugin logs only type names.
            debuggable = false
        }
        install {
            this.name = "heartbeat-logging"
            onStart { log.i { "started" } }
            onStop { error ->
                log.i { "stopped" }
                error?.let { log.e(it) { "store stopped with error" } }
            }
            onIntent { intent ->
                log.logIntent(intent)
                intent
            }
            onAction { action ->
                log.d { "action ${action::class.simpleName ?: "anonymous"}" }
                action
            }
            onState { old, new ->
                // Data updates inside the same state class are visible through the underlying flows; logging
                // every one of them floods the console during streaming.
                if (old::class != new::class) {
                    log.d { "${old::class.simpleName ?: "anonymous"} -> ${new::class.simpleName ?: "anonymous"}" }
                }
                new
            }
        }
        recover { error ->
            if (error is CancellationException) throw error
            log.e(error) { "store operation failed" }
            updateState { onError(error) }
            null
        }
    }
}

/**
 * A screen [store] with its latest state readable synchronously through [current]. Screens start drawing from it,
 * `produceState(model.current.value, model) { model.store.collect { states.collect { value = it } } }`,
 * so the first frame shows the state the model already derived (usually from its machine) instead of a fresh
 * default with a one-frame "loading" flash on every entry. Updates still arrive through the subscription, which
 * also starts the store's `whileSubscribed` plugins such as `reflect`.
 */
public data class ScreenStore<S : MVIState, I : MVIIntent, A : MVIAction> internal constructor(
    public val store: Store<S, I, A>,
    public val current: StateFlow<S>,
)

/** Input and caret events can alternate on every keystroke; all incoming intents require trace mode. */
@HighFrequency
private fun Log.logIntent(intent: MVIIntent) {
    v { "intent ${intent::class.simpleName ?: "anonymous"}" }
}
