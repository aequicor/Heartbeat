package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.script.HarnessWorkflowBase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class HarnessK2SpikeTest {
    @Test
    fun `host caches workflow and immutable leases survive cache replacement and removal`() = runTest {
        val directory = Files.createTempDirectory("harness-host-test")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val host = JvmHarnessScriptHost(directory, "test", backgroundScope, dispatchers)
        val request = HarnessCompilationRequest(
            HarnessId("test"),
            ItemId("workflow"),
            HarnessCodeKind.Workflow,
            "workflow { input -> input }",
        )
        val first = assertIs<HarnessCompilationResult.Success>(host.compile(request))
        try {
            val second = assertIs<HarnessCompilationResult.Success>(
                host.compile(request.copy(source = "workflow { _ -> kotlinx.serialization.json.JsonNull }")),
            )
            second.code.close()
            host.removeCached(request.harness, request.item)
            var definition: WorkflowDefinition? = null
            assertIs<HarnessEvaluationResult.Success>(
                host.evaluate(first.code, HarnessEvaluationContext.Workflow(WorkflowRegistration { definition = it })),
            )
            assertNotNull(definition)
            val lease = first.code.retain()
            try {
                first.code.close()
                first.code.close()
                assertIs<HarnessEvaluationResult.Success>(
                    host.evaluate(lease, HarnessEvaluationContext.Workflow(WorkflowRegistration {})),
                )
            } finally {
                lease.close()
            }
        } finally {
            first.code.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `K2 compiles and evaluates workflow with typed constructor argument`() = runTest {
        val configuration = ScriptCompilationConfiguration {
            baseClass(HarnessWorkflowBase::class)
            dependencies(JvmDependency(HarnessScriptClasspath().entries.map { it.toFile() }))
            compilerOptions("-language-version", "2.4", "-jvm-target", "17")
        }
        var definition: WorkflowDefinition? = null
        val registration = WorkflowRegistration { definition = it }
        val result = BasicJvmScriptingHost().eval(
            StringScriptSource("workflow { input -> input }", "HarnessProbe.kts"),
            configuration,
            ScriptEvaluationConfiguration { constructorArgs(registration) },
        )
        assertIs<ResultWithDiagnostics.Success<EvaluationResult>>(result, result.reports.joinToString { it.message })
        assertIs<ResultValue.Unit>(result.value.returnValue)
        assertNotNull(definition)
    }
}
