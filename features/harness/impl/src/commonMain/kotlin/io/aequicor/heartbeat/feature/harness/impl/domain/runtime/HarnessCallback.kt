package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import kotlinx.coroutines.flow.MutableStateFlow

/** One owned callback. Acquisition linearizes against disposal; an acquired callback may finish afterwards. */
internal class HarnessCallback<H : Any>(val id: Long, val origin: HarnessCallOrigin, handler: H) :
    ScriptRegistration {
    private val handler = MutableStateFlow<H?>(handler)
    private val log = Log.tag("HarnessCallback")
    val isActive: Boolean get() = handler.value != null

    /** Called inside the runtime's actual invocation, immediately before invoking author code. */
    @HighFrequency
    fun acquire(): H? {
        log.v { "acquire script registration" }
        return handler.value
    }

    @HighFrequency
    override fun dispose() {
        log.v { "dispose script registration" }
        handler.value = null
    }
}
