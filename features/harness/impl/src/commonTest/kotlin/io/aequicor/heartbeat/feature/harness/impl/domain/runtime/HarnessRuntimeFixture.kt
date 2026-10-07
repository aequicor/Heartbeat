package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Instant

internal class HarnessRuntimeFixture(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    callOrigins: HarnessCallOrigins? = null,
) {
    var desired = runtimeRequest(1)
    var isEnabled = true
    var isItemPresent = true
    var isHostAvailable = true
    var pendingRemoval: HarnessEffect.Remove? = null
    var libraryGeneration = 1L
    private var removalCounter = 0L
    var isCompilationSuccessful = true
    var isEvaluationSuccessful = true
    var isContextReady = true
    var isPublicationCommitAllowed = true
    var publicationCommits = 0
    var onPublicationCommit: (HarnessInstanceAccess) -> Unit = {}
    var beforeCompile: suspend (HarnessCompilationRequest) -> Unit = {}
    var beforeEvaluation: suspend () -> Unit = {}
    var onCreate: (HarnessInstanceAccess) -> Unit = {}
    var customContext: (HarnessActivationRequest, HarnessInstanceAccess) -> HarnessRuntimeContext? = { _, _ -> null }
    var beforeFeedback: suspend () -> Unit = {}
    var beforeCacheRemoval: suspend () -> Unit = {}
    var contextCloses = 0
    val code = mutableListOf<RuntimeTestCode>()
    val feedback = mutableListOf<HarnessIntent.Internal.ItemRuntimeFailed>()
    val accesses = mutableListOf<HarnessInstanceAccess>()
    val removedCache = mutableListOf<ItemId>()
    val cacheOperations = mutableListOf<String>()
    val host = object : HarnessScriptHost {
        override val isAvailable: Boolean get() = isHostAvailable
        override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
            beforeCompile(request)
            cacheOperations += request.source
            return if (isCompilationSuccessful) {
                val compiled = RuntimeTestCode().also(code::add)
                HarnessCompilationResult.Success(compiled, emptyList())
            } else {
                HarnessCompilationResult.Failure(emptyList())
            }
        }
        override suspend fun evaluate(
            code: CompiledHarnessCode,
            context: HarnessEvaluationContext,
        ): HarnessEvaluationResult {
            beforeEvaluation()
            return if (isEvaluationSuccessful) {
                HarnessEvaluationResult.Success
            } else {
                HarnessEvaluationResult.Failure(emptyList())
            }
        }
        override suspend fun removeCached(harness: HarnessId, item: ItemId) {
            beforeCacheRemoval()
            cacheOperations += "remove"
            removedCache += item
        }
    }
    private val admission = object : HarnessRuntimeAdmission {
        override fun canPublish(request: HarnessActivationRequest): Boolean =
            isEnabled && isItemPresent && request == desired && pendingRemoval?.harness?.id != request.harness.id
        override fun canInvoke(request: HarnessActivationRequest): Boolean =
            isEnabled && isItemPresent && pendingRemoval?.harness?.id != request.harness.id
        override fun canRemoveCached(request: HarnessActivationRequest): Boolean = !isItemPresent
        override fun removalGeneration(effect: HarnessEffect.Remove): Long? =
            libraryGeneration.takeIf { effect == pendingRemoval }
    }
    private val baseContexts = HarnessRuntimeContextFactory { request, access ->
        accesses += access
        onCreate(access)
        customContext(request, access) ?: object : HarnessRuntimeContext {
            override val evaluation = HarnessEvaluationContext.Workflow(WorkflowRegistration {})
            override val isReadyForPublication: Boolean get() = isContextReady
            override fun tryCommitPublication(): Boolean {
                publicationCommits++
                onPublicationCommit(access)
                return isPublicationCommitAllowed
            }
            override fun close() {
                contextCloses++
            }
        }
    }
    private val contexts = object : HarnessRuntimeContextFactory {
        override val origins = callOrigins ?: baseContexts.origins
        override fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessRuntimeContext =
            baseContexts.create(request, access)
    }
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    val runtime = HarnessRuntime(
        host,
        HarnessExecutionLane(dispatcher),
        HarnessRuntimeEnvironment(scope, dispatchers, admission, contexts) {
            beforeFeedback()
            feedback += it
        },
    )

    suspend fun activate(): HarnessInstance {
        check(runtime.activate(desired))
        return checkNotNull(runtime.instance(desired.harness.id, desired.item.id))
    }

    fun deactivation(request: HarnessActivationRequest = desired): HarnessEffect.Deactivate =
        HarnessEffect.Deactivate(listOf(request), false, generation = request.generation)

    fun removal(harness: Harness = desired.harness): HarnessEffect.Remove {
        val current = pendingRemoval
        if (current?.harness == harness) return current
        removalCounter++
        libraryGeneration = maxOf(libraryGeneration, desired.generation)
        return HarnessEffect.Remove(
            harness,
            HarnessReceipt(RequestId("remove$removalCounter"), harness.id, harness.revision, removalCounter),
        ).also { pendingRemoval = it }
    }
}

internal fun runtimeRequest(generation: Long): HarnessActivationRequest {
    val item = HarnessItem.Workflow(ItemId("code"), ItemName("code"), "", "revision$generation")
    val at = Instant.fromEpochMilliseconds(1_000)
    return HarnessActivationRequest(
        Harness(
            HarnessId("owner"), HarnessName("owner"), "", "", HarnessScope.Profile, true,
            listOf(item), ToolPolicySpec(), null, generation, at, at,
        ),
        item,
        generation,
    )
}

internal class RuntimeTestCode : CompiledHarnessCode {
    var closes = 0
    override val kind = HarnessCodeKind.Workflow
    override fun retain(): CompiledHarnessCode = error("Fake host does not borrow")
    override fun close() {
        closes++
    }
}
