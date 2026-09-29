package io.aequicor.heartbeat.core.mvi

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.StoreBuilder
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.recover
import kotlin.reflect.KClass

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
            // Class of the last logged intent: a burst of same-class intents (every keystroke is a
            // DraftChanged) logs once per burst, repeats stay at V for deep tracing.
            var lastIntentClass: KClass<out MVIIntent>? = null
            onStart { log.i { "started" } }
            onStop { error ->
                log.i { "stopped" }
                error?.let { log.e(it) { "store stopped with error" } }
            }
            onIntent { intent ->
                if (intent::class != lastIntentClass) {
                    lastIntentClass = intent::class
                    log.d { "intent ${intent::class.simpleName ?: "anonymous"}" }
                } else {
                    log.v { "intent ${intent::class.simpleName ?: "anonymous"}" }
                }
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
        block()
    }
}
