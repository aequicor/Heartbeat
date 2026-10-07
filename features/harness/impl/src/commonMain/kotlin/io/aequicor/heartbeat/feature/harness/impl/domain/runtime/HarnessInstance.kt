package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One immutable code generation. All lifecycle mutations except job completion are serialized by the runtime. */
internal class HarnessInstance(
    val request: HarnessActivationRequest,
    internal val code: CompiledHarnessCode,
    override val dispatcher: CoroutineDispatcher,
    private val environment: HarnessRuntimeEnvironment,
    onBackgroundFailure: suspend (HarnessInstance) -> Unit,
) : HarnessInstanceAccess {
    private val phase = MutableStateFlow(HarnessInstancePhase.Preparing)
    private val reservations = MutableStateFlow(0)
    private val root = SupervisorJob(environment.scope.coroutineContext[Job])
    private val background = SupervisorJob(root)
    private var context: HarnessRuntimeContext? = null
    private val log = Log.tag("HarnessRuntime")
    private val errors = CoroutineExceptionHandler { _, error ->
        log.w(harnessScriptFailure(error)) { "Instance background callback failed" }
        phase.compareAndSet(HarnessInstancePhase.Preparing, HarnessInstancePhase.PreparationFailed)
        environment.scope.launch(environment.dispatchers.default) { onBackgroundFailure(this@HarnessInstance) }
    }
    private val backgroundContext = environment.scope.coroutineContext + background + dispatcher + errors
    override val scope = HarnessOriginScope(CoroutineScope(backgroundContext), environment.origins)

    /** Host pumps have neutral ancestry; only each delivered envelope supplies author-call provenance. */
    internal val dispatchScope = CoroutineScope(backgroundContext + environment.origins.context(HarnessCallOrigin()))
    internal val calls = HarnessInvocation(
        CoroutineScope(environment.scope.coroutineContext + root + errors),
        environment.dispatchers.default,
        environment.origins,
    )
    internal var consecutiveFailures: Int = 0

    init {
        root.invokeOnCompletion {
            try {
                context?.close()
            } finally {
                code.close()
                log.v { "close instance code" }
                phase.value = HarnessInstancePhase.Closed
            }
        }
    }

    override val isActive: Boolean
        get() = root.isActive && phase.value == HarnessInstancePhase.Active && environment.admission.canInvoke(request)

    override val isRegistrationAllowed: Boolean get() = isPreparing || isActive

    val isClosed: Boolean get() = phase.value == HarnessInstancePhase.Closed

    /** A context reference grants no authority: consumers must enter through HarnessRuntime.invoke. */
    internal val runtimeContext: HarnessRuntimeContext? get() = context
    internal val isPreparing: Boolean
        get() = root.isActive && phase.value == HarnessInstancePhase.Preparing

    override suspend fun awaitPublication(): Boolean = phase.first {
        it != HarnessInstancePhase.Preparing && it != HarnessInstancePhase.Committing
    } == HarnessInstancePhase.Active && isActive

    /** Called inside owned evaluation; a cancelled profile must never construct a fresh service context. */
    internal fun prepareContext(): HarnessEvaluationContext {
        check(root.isActive && isPreparing && context == null)
        val prepared = environment.contexts.create(request, this)
        context = prepared
        return prepared.evaluation
    }

    internal fun publish(): Boolean {
        if (!isPreparing || context?.sealForPublication() != true ||
            !phase.compareAndSet(HarnessInstancePhase.Preparing, HarnessInstancePhase.Committing)
        ) {
            return false
        }
        if (context?.tryCommitPublication() != true) {
            phase.compareAndSet(HarnessInstancePhase.Committing, HarnessInstancePhase.PreparationFailed)
            return false
        }
        val isAllowed = phase.compareAndSet(HarnessInstancePhase.Committing, HarnessInstancePhase.Active)
        if (isAllowed) log.v { "publish instance code" }
        return isAllowed
    }

    internal fun retire() {
        log.v { "retire instance code" }
        phase.update { if (it == HarnessInstancePhase.Closed) it else HarnessInstancePhase.Retiring }
    }

    @HighFrequency
    internal fun reserveCall() {
        log.v { "reserve instance callback" }
        reservations.update { it + 1 }
    }

    @HighFrequency
    internal fun releaseCall() {
        log.v { "release instance callback" }
        reservations.update { it - 1 }
    }

    /** Does not cancel admitted calls: their independent budgets still apply while new admission is closed. */
    internal suspend fun drain() {
        reservations.first { it == 0 }
        calls.awaitIdle()
        background.cancelAndJoin()
        root.cancelAndJoin()
    }
}

private enum class HarnessInstancePhase { Preparing, Committing, PreparationFailed, Active, Retiring, Closed }
