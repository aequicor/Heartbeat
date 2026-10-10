package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import kotlin.coroutines.CoroutineContext

/** Immutable invocation ancestry. Combining origins can add restrictions, never relax an existing restriction. */
internal class HarnessCallOrigin(val isHookRestricted: Boolean = false, sendChain: Map<HarnessId, Int> = emptyMap()) {
    private val chain = sendChain.toMap()

    init {
        require(chain.values.all { it >= 0 }) { "Harness origin depths must be nonnegative" }
    }

    /** A defensive snapshot also prevents a mutable downcast from changing this origin's ancestry. */
    val sendChain: Map<HarnessId, Int> get() = chain.toMap()

    fun merge(other: HarnessCallOrigin): HarnessCallOrigin {
        val combined = chain.toMutableMap()
        other.chain.forEach { (harness, depth) -> combined[harness] = maxOf(combined[harness] ?: 0, depth) }
        return HarnessCallOrigin(isHookRestricted || other.isHookRestricted, combined)
    }

    override fun equals(other: Any?): Boolean = other is HarnessCallOrigin &&
        isHookRestricted == other.isHookRestricted && chain == other.chain

    override fun hashCode(): Int = 31 * isHookRestricted.hashCode() + chain.hashCode()
    override fun toString(): String = "HarnessCallOrigin(***)"
}

/** Coroutine-visible origin used by suspend service checks, paired with the platform carrier by the host. */
internal class HarnessOriginContext(val origin: HarnessCallOrigin) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key
    override fun toString(): String = "HarnessOriginContext(***)"

    companion object Key : CoroutineContext.Key<HarnessOriginContext>
}

/** Profile-owned bridge for synchronous coroutine-context snapshots and suspend service origin checks. */
internal interface HarnessCallOrigins {
    fun current(): HarnessCallOrigin
    fun context(origin: HarnessCallOrigin): CoroutineContext
}
