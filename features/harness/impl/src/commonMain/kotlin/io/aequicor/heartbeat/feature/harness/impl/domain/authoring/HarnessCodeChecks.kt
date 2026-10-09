package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of compiling code before it is put to the user; diagnostics are UI/model data, never logged. */
internal sealed interface CodeCheck {
    data class Compiled(val warnings: List<HarnessCodeDiagnostic>) : CodeCheck {
        override fun toString(): String = "Compiled(warnings=${warnings.size})"
    }

    data class Failed(val diagnostics: List<HarnessCodeDiagnostic>) : CodeCheck {
        override fun toString(): String = "Failed(diagnostics=${diagnostics.size})"
    }

    data object TimedOut : CodeCheck
    data object Unsupported : CodeCheck
}

/**
 * Compiles agent and user code before it is saved. The approval presentation and execution of one call see the
 * same outcome: results are memoized by digest of the exact request. The artifact lease is released immediately;
 * activation compiles again from the host cache. A timeout is not memoized, so a later call can retry.
 */
internal class HarnessCodeChecks(private val host: HarnessScriptHost, private val digest: (String) -> String) {
    private val log = Log.tag("HarnessTools")
    private val lock = Mutex()
    private val memo = MutableStateFlow<Map<String, CodeCheck>>(emptyMap())

    val isAvailable: Boolean get() = host.isAvailable

    suspend fun check(
        harness: HarnessId,
        item: ItemId,
        kind: HarnessCodeKind,
        source: String,
        isAgent: Boolean,
    ): CodeCheck {
        if (!host.isAvailable) return CodeCheck.Unsupported
        val key = digest(listOf(harness.value, item.value, kind.name, source).joinToString("\u0000"))
        memo.value[key]?.let { return it }
        return lock.withLock {
            memo.value[key] ?: compile(HarnessCompilationRequest(harness, item, kind, source, isAgent)).also { result ->
                if (result != CodeCheck.TimedOut) memo.update { (it - key + (key to result)).newest() }
                log.d { "Harness code checked kind=$kind result=$result" }
            }
        }
    }

    private suspend fun compile(request: HarnessCompilationRequest): CodeCheck =
        when (val result = host.compile(request)) {
            is HarnessCompilationResult.Success -> {
                result.code.close()
                CodeCheck.Compiled(result.warnings)
            }

            is HarnessCompilationResult.Failure -> CodeCheck.Failed(
                result.diagnostics.ifEmpty {
                    listOf(HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, "Compilation failed"))
                },
            )

            HarnessCompilationResult.TimedOut -> CodeCheck.TimedOut

            HarnessCompilationResult.Unsupported -> CodeCheck.Unsupported
        }
}

/** Compact listing for the model and the editor, `line:column message`, at most the shared diagnostic limit. */
internal fun List<HarnessCodeDiagnostic>.listing(): String = joinToString("\n") { diagnostic ->
    val place = listOfNotNull(diagnostic.line, diagnostic.column).joinToString(":")
    val severity = if (diagnostic.severity == HarnessDiagnosticSeverity.Error) "error" else "warning"
    listOf(place, severity, diagnostic.message.lineSequence().firstOrNull().orEmpty())
        .filter(String::isNotEmpty).joinToString(" ")
}

/** Insertion order of LinkedHashMap keeps the most recent checks. */
private fun Map<String, CodeCheck>.newest(): Map<String, CodeCheck> =
    if (size <= MEMO) this else entries.drop(size - MEMO).associate { it.key to it.value }

private const val MEMO = 16
