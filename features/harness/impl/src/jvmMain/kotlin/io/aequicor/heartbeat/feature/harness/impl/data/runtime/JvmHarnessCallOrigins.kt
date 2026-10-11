package io.aequicor.heartbeat.feature.harness.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Each profile owns its carrier; installing a coroutine origin never changes another profile's thread local.
 * Every host path into author code installs a carrier explicitly. Code without one ran on a thread or scope the
 * script created itself (a raw thread, a fresh CoroutineScope, a Java callback), so it fails closed: it is treated
 * as hook-restricted and can never schedule an agent turn, spawn a helper or start a workflow.
 *
 * A carrier frozen into an author-owned context (for example `CoroutineScope(scope.coroutineContext + Job())` built
 * during evaluation) does not keep its old ancestry: every child it starts also merges the origin of the launching
 * thread, so launching it from a hook still yields a hook-restricted child.
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
        HarnessOriginContext(origin) + OriginThreadContext(origin, local, threadKey, isPinned = false)

    @HighFrequency
    override fun hostContext(origin: HarnessCallOrigin): CoroutineContext =
        HarnessOriginContext(origin) + OriginThreadContext(origin, local, threadKey, isPinned = true)
}

/**
 * Suspension and dispatcher reuse restore the exact previous value, including absence outside a callback. Children
 * and nested contexts combine monotonically: a copy merges the launching thread's current origin, and an explicitly
 * supplied carrier merges with the inherited one, so neither can relax a restriction of the code that started it.
 * A [isPinned] host pump carrier is the exception: it keeps its own origin, and only the host can create one.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class OriginThreadContext(
    private val origin: HarnessCallOrigin,
    private val local: ThreadLocal<HarnessCallOrigin>,
    private val threadKey: CoroutineContext.Key<OriginThreadContext>,
    private val isPinned: Boolean,
) : AbstractCoroutineContextElement(threadKey),
    CopyableThreadContextElement<HarnessCallOrigin?> {
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

    /** Runs on the launching thread: a child of a cached neutral context still inherits a hook's restriction. */
    @HighFrequency
    override fun copyForChild(): CopyableThreadContextElement<HarnessCallOrigin?> =
        if (isPinned) this else launchedWith(origin)

    @HighFrequency
    override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext {
        if (isPinned) return overwritingElement
        val overwriting = (overwritingElement as? OriginThreadContext)?.origin ?: HarnessCallOrigin()
        return launchedWith(origin.merge(overwriting))
    }

    private fun launchedWith(base: HarnessCallOrigin): OriginThreadContext =
        OriginThreadContext(local.get()?.let(base::merge) ?: base, local, threadKey, isPinned = false)

    override fun toString(): String = "HarnessOriginThreadContext(***)"
}

/** Origin of author code that escaped every host carrier; restrictions are monotone, so this only narrows. */
private val DETACHED = HarnessCallOrigin(isHookRestricted = true)
