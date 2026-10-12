package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits

/**
 * The host's refusal of a documented, self-healing limit (the per-chat harness turn streak). It ends an author
 * callback that does not catch it like any other exception, but it is the host's decision, not a fault of the
 * script: the runtime logs it without a warning and never counts it towards [HarnessLimits.FAILURES], so legitimate
 * background work (timers, launched loops) is not disabled for reaching the limit. The type is internal to the host;
 * even a rethrown instance grants nothing a script could not get by catching its own failures.
 */
internal class HarnessLimitRefusal(message: String) : IllegalStateException(message)

/** True also when author code wrapped the refusal into its own exception; cycles in causes are tolerated. */
internal fun Throwable.isHarnessLimitRefusal(): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is HarnessLimitRefusal) return true
        current = current.cause
    }
    return false
}
