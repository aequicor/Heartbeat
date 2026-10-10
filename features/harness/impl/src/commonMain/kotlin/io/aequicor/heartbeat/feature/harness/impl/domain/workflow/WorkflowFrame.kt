package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext

/** One sequential DSL branch. Concurrent work must use parallel, which allocates separate structural branches. */
internal class WorkflowFrame(val owner: Any, val path: String) : CoroutineContext.Element {
    private val busy = MutableStateFlow(false)
    private var sequence = 0
    override val key: CoroutineContext.Key<*> get() = Key

    @HighFrequency
    fun enter(): WorkflowPosition? {
        if (!busy.compareAndSet(false, true)) return null
        frameLog.v { "Workflow frame entered" }
        val index = sequence++
        return WorkflowPosition(StepKey("${path}s$index"), "${path}p$index/")
    }

    @HighFrequency
    fun leave() {
        busy.value = false
        frameLog.v { "Workflow frame left" }
    }

    override fun toString(): String = "WorkflowFrame(***)"
    companion object Key : CoroutineContext.Key<WorkflowFrame>
}

private val frameLog = Log.tag("HarnessWorkflow")

internal data class WorkflowPosition(val key: StepKey, val children: String)
