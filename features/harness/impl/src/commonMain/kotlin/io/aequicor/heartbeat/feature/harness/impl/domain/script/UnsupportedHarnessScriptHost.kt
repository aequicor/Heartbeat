package io.aequicor.heartbeat.feature.harness.impl.domain.script

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId

/** Mobile platforms expose the same contract without creating files, compiler objects or execution scopes. */
internal class UnsupportedHarnessScriptHost : HarnessScriptHost {
    override val isAvailable: Boolean = false

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult =
        HarnessCompilationResult.Unsupported

    override suspend fun evaluate(
        code: CompiledHarnessCode,
        context: HarnessEvaluationContext,
    ): HarnessEvaluationResult = HarnessEvaluationResult.Unsupported

    override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
}
