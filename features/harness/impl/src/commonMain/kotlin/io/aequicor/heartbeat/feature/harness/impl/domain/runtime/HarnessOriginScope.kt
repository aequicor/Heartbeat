package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * Stable activation scope whose context is captured when a builder reads it. A script may cache this object
 * before entering a hook: launching through that cached scope still inherits the current hook restriction and
 * send ancestry. The base job and dispatcher remain owned by the activation, independently of callback return.
 * A context copied out of this scope freezes only its snapshot; the platform carrier merges the launching thread's
 * origin into every child, and the per-session harness turn streak bounds carriers that escape both.
 */
internal class HarnessOriginScope(private val base: CoroutineScope, private val origins: HarnessCallOrigins) :
    CoroutineScope {
    @get:HighFrequency
    override val coroutineContext: CoroutineContext
        get() {
            val context = base.coroutineContext
            val inherited = context[HarnessOriginContext]?.origin ?: HarnessCallOrigin()
            return context + origins.context(inherited.merge(origins.current()))
        }
}
