package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.impl.data.runtime.StagedHarnessContexts
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext

/** Real publication and dispatch with an in-memory evaluator; no compiler or external session services. */
internal class HarnessDispatchFixture(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    callOrigins: HarnessCallOrigins? = null,
) {
    var isEnabled = true
    var isSessionAllowed = true
    var registrationOrigin = HarnessCallOrigin()
    var onEvaluate: suspend (HarnessScriptScope) -> Unit = {}
    val feedback = mutableListOf<HarnessIntent.Internal.ItemRuntimeFailed>()
    var desired = runtimeRequest(1).let { original ->
        val item = HarnessItem.Script(original.item.id, original.item.name, "", "fixture")
        original.copy(harness = original.harness.copy(items = listOf(item)), item = item)
    }
    val request get() = desired
    val context = SessionHookContext(dispatchSession, null, null, null, SessionOwner("owner"))
    val call = HookedToolCall(context, "read", AgentToolAction.Read, JsonObject(emptyMap()))
    private val origins = callOrigins ?: object : HarnessCallOrigins {
        override fun current(): HarnessCallOrigin = registrationOrigin
        override fun context(origin: HarnessCallOrigin): CoroutineContext = HarnessOriginContext(origin)
    }
    private val host = object : HarnessScriptHost {
        override val isAvailable = true
        override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult =
            HarnessCompilationResult.Success(DispatchTestCode(), emptyList())
        override suspend fun evaluate(
            code: CompiledHarnessCode,
            context: HarnessEvaluationContext,
        ): HarnessEvaluationResult {
            onEvaluate((context as HarnessEvaluationContext.Script).scope)
            return HarnessEvaluationResult.Success
        }
        override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
    }
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    private val admission = object : HarnessRuntimeAdmission {
        override fun canPublish(request: HarnessActivationRequest): Boolean = isEnabled && request == desired
        override fun canInvoke(request: HarnessActivationRequest): Boolean = isEnabled
    }
    val runtime = HarnessRuntime(
        host,
        HarnessExecutionLane(dispatcher),
        HarnessRuntimeEnvironment(scope, dispatchers, admission, StagedHarnessContexts(origins)) { feedback += it },
    )
    val sessions = HarnessSessionAdmission { _, session -> isSessionAllowed && session == context.session }
    val events = HarnessEventDispatch(runtime, sessions)
    val hooks = HarnessHookDispatch(runtime, sessions)

    suspend fun activate(): HarnessInstance {
        check(runtime.activate(request))
        registrationOrigin = HarnessCallOrigin()
        return checkNotNull(runtime.instance(request.harness.id, request.item.id))
    }

    fun deactivation(): HarnessEffect.Deactivate =
        HarnessEffect.Deactivate(listOf(request), false, generation = request.generation)
}

internal val dispatchSession = SessionRef(EngineId("engine"), SessionSourceId("source"), "session")

private class DispatchTestCode : CompiledHarnessCode {
    override val kind = HarnessCodeKind.Script
    override fun retain(): CompiledHarnessCode = DispatchTestCode()
    override fun close() = Unit
}
