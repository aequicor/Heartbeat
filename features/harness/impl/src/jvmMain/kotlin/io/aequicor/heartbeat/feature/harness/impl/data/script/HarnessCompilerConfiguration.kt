package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptBase
import io.aequicor.heartbeat.feature.harness.api.script.HarnessWorkflowBase
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.JvmDependency

/** Builder execution stores only serializable values, without refinement callbacks or captured services. */
internal fun harnessCompilerConfiguration(kind: HarnessCodeKind, classpath: HarnessScriptClasspath) =
    ScriptCompilationConfiguration {
        when (kind) {
            HarnessCodeKind.Script -> baseClass(HarnessScriptBase::class)
            HarnessCodeKind.Workflow -> baseClass(HarnessWorkflowBase::class)
        }
        dependencies(JvmDependency(classpath.entries.map { it.toFile() }))
        compilerOptions("-language-version", "2.4", "-jvm-target", "17")
        defaultImports(
            "io.aequicor.heartbeat.feature.harness.api.ItemName",
            "io.aequicor.heartbeat.feature.harness.api.script.on",
            "io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions",
            "kotlinx.coroutines.launch",
            "kotlinx.serialization.json.JsonElement",
            "kotlinx.serialization.json.JsonObject",
            "kotlinx.serialization.json.JsonPrimitive",
            "kotlinx.serialization.json.JsonNull",
            "kotlinx.serialization.json.buildJsonObject",
            "kotlinx.serialization.json.put",
            "kotlin.time.Duration.Companion.seconds",
            "kotlin.time.Duration.Companion.minutes",
            "kotlin.time.Duration.Companion.hours",
        )
    }

/** Source diagnostics are bounded UI data; exception objects are deliberately never copied. */
internal fun List<ScriptDiagnostic>.harnessDiagnostics(): List<HarnessCodeDiagnostic> =
    asSequence().filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
        .take(MAX_HARNESS_DIAGNOSTICS).map {
            HarnessCodeDiagnostic(
                if (it.severity >= ScriptDiagnostic.Severity.ERROR) {
                    HarnessDiagnosticSeverity.Error
                } else {
                    HarnessDiagnosticSeverity.Warning
                },
                it.message.take(MAX_HARNESS_DIAGNOSTIC_CHARS),
                it.location?.start?.line,
                it.location?.start?.col,
            )
        }.toList()

internal fun harnessHostFailure() = listOf(
    HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, "Harness code operation failed"),
)
