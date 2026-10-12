package io.aequicor.heartbeat.feature.harness.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallback
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInvocationBudget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInvocationHandle
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInvocationOptions
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessTimerInvoker
import kotlinx.coroutines.flow.first

/** Lazy runtime access breaks the staged-context cycle. A timer never acquires a replacement's authority. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class RuntimeHarnessTimerInvoker(private val runtime: Lazy<HarnessRuntime>) : HarnessTimerInvoker {
    override suspend fun awaitPublication(target: HarnessInstanceTarget): Boolean {
        if (!target.access.awaitPublication()) return false
        val instances = runtime.value.publishedInstances.first { instances ->
            !target.access.isActive || instances.any { it === target.access }
        }
        return target.access.isActive && instances.any { it === target.access && it.request == target.request }
    }

    override suspend fun invoke(target: HarnessInstanceTarget, callback: HarnessCallback<suspend () -> Unit>): Boolean {
        val instance = runtime.value.instance(target.request.harness.id, target.request.item.id) ?: return false
        if (instance !== target.access || instance.request != target.request || !callback.isActive) return false
        var execution: HarnessInvocationHandle? = null
        val result = runtime.value.invoke(
            instance,
            HarnessInvocationBudget.Event,
            HarnessInvocationOptions(origin = callback.origin, onStarted = { execution = it }),
        ) { callback.acquire()?.invoke() }
        // Logical timeout does not prove termination. Retain the single timer pump until actual completion.
        execution?.awaitStopped()
        return result != null
    }
}
