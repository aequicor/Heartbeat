package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/** Synchronous reads of the current committed library; implementations perform no IO or user callbacks. */
internal interface HarnessRuntimeAdmission {
    /** Requires the exact enabled Pending revision/generation, independently of delayed effect delivery. */
    fun canPublish(request: HarnessActivationRequest): Boolean

    /** An old published revision may serve during replacement, but never after disable or suspension. */
    fun canInvoke(request: HarnessActivationRequest): Boolean

    /** True only when the committed item identity is absent; disabling or changing its kind keeps its cache. */
    fun canRemoveCached(request: HarnessActivationRequest): Boolean = false

    /** Activation generation of the exact pending deletion receipt; null means this effect is obsolete. */
    fun removalGeneration(effect: HarnessEffect.Remove): Long? = null
}

/** Authority supplied to staged services. Holding it never extends an activation's lifetime. */
internal interface HarnessInstanceAccess {
    val scope: CoroutineScope
    val dispatcher: CoroutineDispatcher
    val isActive: Boolean
    val isRegistrationAllowed: Boolean get() = isActive
    suspend fun awaitPublication(): Boolean
}

/** Context is private to one instance; its close hook only releases host registrations and must not run user code. */
internal interface HarnessRuntimeContext {
    val evaluation: HarnessEvaluationContext

    /** Validates host registrations after evaluation, without executing user callbacks. */
    val isReadyForPublication: Boolean get() = true

    /** Freezes host-owned declaration snapshots; called under the publication mutex, without author code. */
    fun sealForPublication(): Boolean = isReadyForPublication

    /**
     * Atomically commits shared registration quotas after the instance entered its closed Committing phase,
     * before it grants invocation authority or wakes publication waiters. False leaves the previous generation's
     * quotas unchanged. Implementations
     * run no author code, never suspend, and must not throw after committing external state.
     */
    fun tryCommitPublication(): Boolean = true
    fun close()
}

/** Builds staged services; externally visible dispatch reads only successfully published instances. */
internal fun interface HarnessRuntimeContextFactory {
    val origins: HarnessCallOrigins get() = MarkerHarnessCallOrigins
    fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessRuntimeContext
}

/** Profile lifetime and external ports; the domain runtime neither constructs DI nor reads persistent data. */
internal data class HarnessRuntimeEnvironment(
    val scope: CoroutineScope,
    val dispatchers: DispatcherProvider,
    val admission: HarnessRuntimeAdmission,
    val contexts: HarnessRuntimeContextFactory,
    val reportFailure: suspend (HarnessIntent.Internal.ItemRuntimeFailed) -> Unit,
) {
    val origins: HarnessCallOrigins get() = contexts.origins
    override fun toString(): String = "HarnessRuntimeEnvironment(***)"
}

/** Marker-only default for pure lifecycle fixtures; executing platforms inject their scope carrier. */
private object MarkerHarnessCallOrigins : HarnessCallOrigins {
    override fun current(): HarnessCallOrigin = HarnessCallOrigin()
    override fun context(origin: HarnessCallOrigin): CoroutineContext = HarnessOriginContext(origin)
}
