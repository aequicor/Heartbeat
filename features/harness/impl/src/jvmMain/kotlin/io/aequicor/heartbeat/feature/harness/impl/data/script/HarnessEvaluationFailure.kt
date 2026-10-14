package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.api.HarnessValidationException
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult

/**
 * Scripting can wrap constructor errors in reflection exceptions. Inspect a bounded cause chain for a typed
 * host validation code only. Never copy messages, filenames, stack frames or source from arbitrary throwables.
 */
internal fun harnessEvaluationFailure(errors: List<Throwable>): HarnessEvaluationResult.Failure {
    val reason = errors.firstNotNullOfOrNull { error ->
        generateSequence(error) { it.cause }.take(MAX_CAUSES)
            .filterIsInstance<HarnessValidationException>().firstOrNull()?.code
    }
    val diagnostics = reason?.let { listOf(HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, it.message)) }
        ?: harnessHostFailure()
    return HarnessEvaluationResult.Failure(diagnostics, reason)
}

private const val MAX_CAUSES = 8
