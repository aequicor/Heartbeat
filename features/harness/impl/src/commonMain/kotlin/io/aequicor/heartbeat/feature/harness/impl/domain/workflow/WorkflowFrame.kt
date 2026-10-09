package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext

/** One sequential DSL branch. Concurrent work must use parallel, which allocates separate structural branches. */
internal class WorkflowFrame(val owner: Any, val path: String) : CoroutineContext.Element {
    private val busy = MutableStateFlow(false)
    private var sequence = 0
    override val key: CoroutineContext.Key<*> get() = Key

    fun enter(): WorkflowPosition? {
        if (!busy.compareAndSet(false, true)) return null
        val index = sequence++
        return WorkflowPosition(StepKey("${path}s$index"), "${path}p$index/")
    }

    fun leave() {
        busy.value = false
    }

    override fun toString(): String = "WorkflowFrame(***)"
    companion object Key : CoroutineContext.Key<WorkflowFrame>
}

internal data class WorkflowPosition(val key: StepKey, val children: String)
