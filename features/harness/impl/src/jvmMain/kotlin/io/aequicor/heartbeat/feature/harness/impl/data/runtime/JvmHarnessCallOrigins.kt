package io.aequicor.heartbeat.feature.harness.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Each profile owns its carrier; installing a coroutine origin never changes another profile's thread local.
 * Every host path into author code installs a carrier explicitly. Code without one ran on a thread or scope the
 * script created itself (a raw thread, a fresh CoroutineScope, a Java callback), so it fails closed: it is treated
 * as hook-restricted and can never schedule an agent turn, spawn a helper or start a workflow.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmHarnessCallOrigins : HarnessCallOrigins {
    private val local = ThreadLocal<HarnessCallOrigin>()
    private val threadKey = object : CoroutineContext.Key<OriginThreadContext> {}

    @HighFrequency
    override fun current(): HarnessCallOrigin = local.get() ?: DETACHED

    @HighFrequency
    override fun context(origin: HarnessCallOrigin): CoroutineContext =
        HarnessOriginContext(origin) + OriginThreadContext(origin, local, threadKey)
}

/** Suspension and dispatcher reuse restore the exact previous value, including absence outside a callback. */
private class OriginThreadContext(
    private val origin: HarnessCallOrigin,
    private val local: ThreadLocal<HarnessCallOrigin>,
    key: CoroutineContext.Key<OriginThreadContext>,
) : AbstractCoroutineContextElement(key),
    ThreadContextElement<HarnessCallOrigin?> {
    @HighFrequency
    override fun updateThreadContext(context: CoroutineContext): HarnessCallOrigin? {
        val previous = local.get()
        local.set(origin)
        return previous
    }

    @HighFrequency
    override fun restoreThreadContext(context: CoroutineContext, oldState: HarnessCallOrigin?) {
        if (oldState == null) local.remove() else local.set(oldState)
    }

    override fun toString(): String = "HarnessOriginThreadContext(***)"
}

/** Origin of author code that escaped every host carrier; restrictions are monotone, so this only narrows. */
private val DETACHED = HarnessCallOrigin(isHookRestricted = true)
