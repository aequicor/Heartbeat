package io.aequicor.heartbeat.feature.harness.impl.domain.script

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import kotlinx.serialization.Serializable

/** The generated superclass, included in the cache key even when source text is unchanged. */
@Serializable
internal enum class HarnessCodeKind { Script, Workflow }

/** A compiler request is private code, not a diagnostic/logging value. Item identity is harness-scoped. */
internal data class HarnessCompilationRequest(
    val harness: HarnessId,
    val item: ItemId,
    val kind: HarnessCodeKind,
    val source: String,
    val isAgentInitiated: Boolean = false,
) {
    init {
        require(source.length <= HarnessLimits.SOURCE_CHARS)
    }

    override fun toString(): String = "HarnessCompilationRequest(kind=$kind, ***)"
}

/** Compiler messages are UI-only private content; never log their message, source line or embedded exception. */
@Serializable
internal data class HarnessCodeDiagnostic(
    val severity: HarnessDiagnosticSeverity,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
) {
    override fun toString(): String = "HarnessCodeDiagnostic(severity=$severity, ***)"
}

@Serializable
internal enum class HarnessDiagnosticSeverity { Warning, Error }

/** Platform-owned compiled code. Holding it never grants activation or session authority. */
internal interface CompiledHarnessCode {
    val kind: HarnessCodeKind

    /** Creates another owned lease, used by pinned workflows independently of a live script activation. */
    fun retain(): CompiledHarnessCode

    /**
     * Idempotently releases this lease. The caller must revoke and drain callbacks before release; closing an
     * artifact is not a cancellation barrier. Last release frees its immutable class bytes and loader references.
     */
    fun close()
}

internal sealed interface HarnessCompilationResult {
    /** Transfers one owned artifact lease to the caller, including compile-for-approval callers. */
    data class Success(val code: CompiledHarnessCode, val warnings: List<HarnessCodeDiagnostic>) :
        HarnessCompilationResult {
        override fun toString(): String = "HarnessCompilationResult.Success(***)"
    }

    data class Failure(val diagnostics: List<HarnessCodeDiagnostic>) : HarnessCompilationResult {
        override fun toString(): String = "HarnessCompilationResult.Failure(***)"
    }

    data object TimedOut : HarnessCompilationResult
    data object Unsupported : HarnessCompilationResult
}

/** Constructor arguments belong to the current approved activation, never to a serialized compiler configuration. */
internal sealed interface HarnessEvaluationContext {
    data class Script(val scope: HarnessScriptScope) : HarnessEvaluationContext {
        override fun toString(): String = "HarnessEvaluationContext.Script(***)"
    }

    data class Workflow(val registration: WorkflowRegistration) : HarnessEvaluationContext {
        override fun toString(): String = "HarnessEvaluationContext.Workflow(***)"
    }
}

internal sealed interface HarnessEvaluationResult {
    data object Success : HarnessEvaluationResult
    data class Failure(val diagnostics: List<HarnessCodeDiagnostic>) : HarnessEvaluationResult {
        override fun toString(): String = "HarnessEvaluationResult.Failure(***)"
    }

    data object Unsupported : HarnessEvaluationResult
}

/**
 * Compilation and evaluation only. The later runtime owns approval, activation, the evaluation lane and its budget.
 * Compile waiters may cancel or time out without cancelling profile-owned compiler work. Evaluation runs on the
 * caller's dispatcher and must be invoked from the runtime's bounded lane, never from the UI thread. It borrows
 * its own lease for the call. Compile-for-approval callers release Success in finally; active/pinned runtime
 * callers release only after their registrations and in-flight calls have drained. Timed-out or cancelled
 * compile waiters never own a returned artifact; the host releases an eventual unclaimed result.
 */
internal interface HarnessScriptHost {
    /** Cheap platform capability, independent of approval and item activation. */
    val isAvailable: Boolean

    suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult
    suspend fun evaluate(code: CompiledHarnessCode, context: HarnessEvaluationContext): HarnessEvaluationResult
    suspend fun removeCached(harness: HarnessId, item: ItemId)
}
