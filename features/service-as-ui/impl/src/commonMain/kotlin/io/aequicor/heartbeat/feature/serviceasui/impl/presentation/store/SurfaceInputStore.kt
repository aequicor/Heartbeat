package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.api.Store

/** Immutable identity of one surface authority; it contains no owner data and never changes on a queued event. */
@Immutable
internal class SurfaceInputBinding

private typealias SurfaceStore = Store<SurfaceState, SurfaceIntent, SurfaceAction>
private typealias SurfaceProvider = Provider<SurfaceState, SurfaceIntent, SurfaceAction>

/** Captures authority before FlowMVI enqueues input, including input sent through a subscription provider. */
internal class SurfaceInputStore(
    private val delegate: SurfaceStore,
    private val currentBinding: () -> SurfaceInputBinding,
) : SurfaceStore by delegate {
    override fun intent(intent: SurfaceIntent) = delegate.intent(bound(intent))

    override suspend fun emit(intent: SurfaceIntent) = delegate.emit(bound(intent))

    override fun CoroutineScope.subscribe(block: suspend SurfaceProvider.() -> Unit): Job {
        val scope = this
        return with(delegate) {
            scope.subscribe {
                val provider = this
                block(
                    object : SurfaceProvider by provider {
                        override fun intent(intent: SurfaceIntent) = this@SurfaceInputStore.intent(intent)

                        override suspend fun emit(intent: SurfaceIntent) = this@SurfaceInputStore.emit(intent)
                    },
                )
            }
        }
    }

    private fun bound(intent: SurfaceIntent): SurfaceIntent.Bound =
        intent as? SurfaceIntent.Bound ?: SurfaceIntent.Bound(currentBinding(), intent)
}

/** A view keeps the binding of the state it drew, even if its callback arrives after a rebind or recomposition. */
internal fun SurfaceStore.inputsFor(state: SurfaceState): (SurfaceIntent) -> Unit {
    val binding = state.inputBinding
    return { input -> if (binding != null) intent(SurfaceIntent.Bound(binding, input)) }
}
