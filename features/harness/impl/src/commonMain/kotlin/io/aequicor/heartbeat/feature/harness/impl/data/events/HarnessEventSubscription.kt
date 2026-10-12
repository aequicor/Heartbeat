package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Installs the initial hot observer before returning, in the supplied enabled-branch lifetime. */
internal fun interface HarnessEventSubscription<T> {
    fun subscribe(scope: CoroutineScope, consume: suspend (T) -> Unit)
}

/** Direct hot stream subscription; no channel operator postpones the initial collector attachment. */
internal class HarnessFlowEvents<T>(private val events: Flow<T>) : HarnessEventSubscription<T> {
    private val log = Log.tag("HarnessRuntime")

    @HighFrequency
    override fun subscribe(scope: CoroutineScope, consume: suspend (T) -> Unit) {
        log.v { "attach harness event subscription" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { if (isActive) consume(it) }
        }
    }
}

/**
 * StateFlow's initial value is collected in place, so its current machine output is attached before subscribe
 * returns. Replacements revoke the old generation before joining its collector. The identity and generation
 * checks reject delayed old outputs even while its non-cancellable cleanup is still draining.
 */
internal class HarnessSwitchingEvents<M : Any, T>(
    private val machines: StateFlow<M?>,
    private val events: (M) -> Flow<T>,
) : HarnessEventSubscription<T> {
    private val log = Log.tag("HarnessRuntime")

    @HighFrequency
    override fun subscribe(scope: CoroutineScope, consume: suspend (T) -> Unit) {
        log.v { "attach switching harness event subscription" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val generation = MutableStateFlow<Any?>(null)
            var observed: M? = null
            var collector: Job? = null
            machines.collect { machine ->
                if (observed === machine) return@collect
                observed = machine
                generation.value = null
                collector?.cancelAndJoin()
                collector = null
                if (machine != null && machines.value === machine) {
                    val token = Any()
                    generation.value = token
                    collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        events(machine).collect {
                            if (isActive && generation.value === token && machines.value === machine) consume(it)
                        }
                    }
                }
            }
        }
    }
}
