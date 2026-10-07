package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Runs only fixed fixtures with private empty service implementations, never a profile or external agent. */
internal suspend fun exerciseHarnessHost(environment: HarnessProbeEnvironment, parent: CoroutineScope) {
    withProbeHost(environment, parent) { host, scope ->
        compileAndEvaluate(host, scope, HarnessCodeKind.Script)
        compileAndEvaluate(host, scope, HarnessCodeKind.Workflow)
        val invalid = host.compile(
            probeRequest(HarnessCodeKind.Script).copy(
                item = ItemId("broken"),
                source = "val broken = " + "probe_unresolved_".repeat(256),
            ),
        )
        if (invalid is HarnessCompilationResult.Success) invalid.code.close()
        checkProbe(invalid is HarnessCompilationResult.Failure, "InvalidSource")
        val diagnostics = (invalid as HarnessCompilationResult.Failure).diagnostics
        checkProbe(diagnostics.isNotEmpty() && diagnostics.size <= MAX_HARNESS_DIAGNOSTICS, "DiagnosticsCount")
        checkProbe(diagnostics.all { it.message.length <= MAX_HARNESS_DIAGNOSTIC_CHARS }, "DiagnosticsLength")
    }
    val files = cacheFiles(environment.directory)
    checkProbe(files.count { it.fileName.toString().endsWith(".jar") } == 2, "CacheArtifacts")
    checkProbe(files.count { it.fileName.toString().endsWith(".json") } == 2, "CacheMetadata")
    files.forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(CACHE_MARKER_MILLIS)) }
    val stored = files.associateWith(::cacheSnapshot)
    withProbeHost(environment, parent) { host, scope ->
        compileAndEvaluate(host, scope, HarnessCodeKind.Script)
        compileAndEvaluate(host, scope, HarnessCodeKind.Workflow)
    }
    checkProbe(cacheFiles(environment.directory).toSet() == files.toSet(), "CacheReloadFiles")
    checkProbe(files.all { cacheSnapshot(it) == stored[it] }, "CacheReloadWrites")
}

private suspend fun withProbeHost(
    environment: HarnessProbeEnvironment,
    parent: CoroutineScope,
    body: suspend (HarnessScriptHost, CoroutineScope) -> Unit,
) {
    val owner = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + owner)
    try {
        body(environment.createHost(scope), scope)
    } finally {
        withContext(NonCancellable) { owner.cancelAndJoin() }
    }
}

private suspend fun compileAndEvaluate(host: HarnessScriptHost, scope: CoroutineScope, kind: HarnessCodeKind) {
    val compiled = host.compile(probeRequest(kind))
    if (compiled is HarnessCompilationResult.Failure) {
        // This standalone probe compiles only constants below, never profile content. Bounded diagnostics
        // make missing release-image resources or stripped metadata actionable in CI.
        compiled.diagnostics.forEach { diagnostic ->
            Log.tag("HarnessHostProbe").w { "Fixed compiler fixture: ${diagnostic.message}" }
        }
    }
    checkProbe(compiled is HarnessCompilationResult.Success, "Compile${kind.name}")
    val code = (compiled as HarnessCompilationResult.Success).code
    try {
        when (kind) {
            HarnessCodeKind.Script -> evaluateProbeScript(host, code, scope)
            HarnessCodeKind.Workflow -> evaluateProbeWorkflow(host, code)
        }
    } finally {
        code.close()
    }
}

internal suspend fun checkProbeEvaluation(
    host: HarnessScriptHost,
    code: CompiledHarnessCode,
    context: HarnessEvaluationContext,
) {
    checkProbe(host.evaluate(code, context) == HarnessEvaluationResult.Success, "Evaluation")
}

internal fun checkProbe(value: Boolean, stage: String) {
    if (!value) throw HarnessProbeFailure(stage)
}

private fun probeRequest(kind: HarnessCodeKind): HarnessCompilationRequest = HarnessCompilationRequest(
    HarnessId("probe"),
    ItemId(if (kind == HarnessCodeKind.Script) "script" else "workflow"),
    kind,
    if (kind == HarnessCodeKind.Script) SCRIPT_SOURCE else WORKFLOW_SOURCE,
)

private fun cacheFiles(directory: Path): List<Path> = Files.walk(directory).use { paths ->
    paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".jar") || it.toString().endsWith(".json")) }
        .sorted().toList()
}

private fun cacheSnapshot(path: Path): Pair<List<Byte>, FileTime> =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList() to Files.getLastModifiedTime(path)

private const val CACHE_MARKER_MILLIS = 946684800000L
private val SCRIPT_SOURCE = """
    import io.aequicor.heartbeat.feature.harness.api.HarnessId
    import io.aequicor.heartbeat.feature.harness.api.ItemName
    import kotlinx.coroutines.launch
    check(script.harness == HarnessId("probe"))
    script.scope.launch {
        prompts.render(ItemName("probe"), mapOf("item" to script.item.value, "name" to script.name.value))
    }
""".trimIndent()
private val WORKFLOW_SOURCE = """
    import kotlinx.serialization.json.JsonPrimitive
    workflow { arguments ->
        check(arguments == input)
        step("probe") { JsonPrimitive("workflow-ok") }
    }
""".trimIndent()
