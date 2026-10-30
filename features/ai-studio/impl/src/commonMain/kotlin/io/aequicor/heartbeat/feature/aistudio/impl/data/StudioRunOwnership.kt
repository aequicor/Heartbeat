package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Transfers a host delivery's settlement from its waiting caller to the profile worker. The coordinator captures
 * this element before starting the worker and takes ownership before its first suspension. Once transferred, only
 * the worker may settle the delivery, including when its caller stops waiting. A missing outcome is not a failure
 * receipt: only a cancelled submission gate proves that native submission never began.
 */
internal class StudioRunOwnership(val settle: suspend (outcome: RunOutcome?, isUnsubmitted: Boolean) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    private val log = Log.tag("StudioRunOwnership")
    private val transferred = MutableStateFlow(false)
    val isTransferred: Boolean get() = transferred.value

    fun transfer() {
        check(transferred.compareAndSet(false, true)) { "Delivery already has a profile owner" }
        log.v { "Transferred delivery settlement to the profile worker" }
    }

    companion object Key : CoroutineContext.Key<StudioRunOwnership>
}
