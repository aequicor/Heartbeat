package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

internal class HarnessRegistrationFixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
    var desired = scriptRequest(1)
    var isEnabled = true
    var isEvaluationSuccessful = true
    var evaluate: suspend (HarnessScriptScope) -> Unit = {}
    val scripts = mutableListOf<HarnessScriptScope>()
    val origins = RegistrationTestOrigins()
    private val admission = object : HarnessRuntimeAdmission {
        override fun canPublish(request: HarnessActivationRequest) = isEnabled && request == desired
        override fun canInvoke(request: HarnessActivationRequest) = isEnabled
    }
    private val contexts = object : HarnessRuntimeContextFactory {
        override val origins = this@HarnessRegistrationFixture.origins
        override fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess) =
            HarnessScriptContext(request, access, origins)
    }
    private val host = object : HarnessScriptHost {
        override val isAvailable = true
        override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult =
            HarnessCompilationResult.Success(
                object : CompiledHarnessCode {
                    override val kind = HarnessCodeKind.Script
                    override fun retain(): CompiledHarnessCode = error("Unexpected borrow")
                    override fun close() = Unit
                },
                emptyList(),
            )
        override suspend fun evaluate(
            code: CompiledHarnessCode,
            context: HarnessEvaluationContext,
        ): HarnessEvaluationResult {
            val script = (context as HarnessEvaluationContext.Script).scope
            scripts += script
            evaluate(script)
            return if (isEvaluationSuccessful) {
                HarnessEvaluationResult.Success
            } else {
                HarnessEvaluationResult.Failure(
                    emptyList(),
                )
            }
        }
        override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
    }
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    val runtime = HarnessRuntime(
        host,
        HarnessExecutionLane(dispatcher),
        HarnessRuntimeEnvironment(scope, dispatchers, admission, contexts) {},
    )
    suspend fun activate(): HarnessInstance {
        check(runtime.activate(desired))
        return checkNotNull(runtime.instance(desired.harness.id, desired.item.id))
    }
}

internal class RegistrationTestOrigins : HarnessCallOrigins {
    var origin = HarnessCallOrigin()
    override fun current() = origin
    override fun context(origin: HarnessCallOrigin) = HarnessOriginContext(origin)
}

internal fun scriptRequest(generation: Long, item: String = "code", owner: String = "owner"): HarnessActivationRequest {
    val base = runtimeRequest(generation)
    val code = HarnessItem.Script(ItemId(item), ItemName(item), "", "revision$generation")
    return base.copy(
        harness = base.harness.copy(id = HarnessId(owner), name = HarnessName(owner), items = listOf(code)),
        item = code,
    )
}
