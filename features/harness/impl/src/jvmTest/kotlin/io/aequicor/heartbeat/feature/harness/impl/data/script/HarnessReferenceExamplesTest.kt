package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.EXAMPLE_SCREEN_WORKFLOW
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.EXAMPLE_VERIFY_SCRIPT
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.forbiddenConstruct
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The examples agents copy from harness_api_reference must compile against the current script API. */
class HarnessReferenceExamplesTest {
    @Test
    fun `reference examples compile without warnings and pass authoring checks`() = runTest {
        val directory = Files.createTempDirectory("harness-reference-test")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val host = JvmHarnessScriptHost(directory, "test", backgroundScope, dispatchers)
        try {
            listOf(
                HarnessCodeKind.Script to EXAMPLE_VERIFY_SCRIPT,
                HarnessCodeKind.Workflow to EXAMPLE_SCREEN_WORKFLOW,
            ).forEach { (kind, source) ->
                assertNull(forbiddenConstruct(source))
                assertEquals(emptyList(), validateHarnessSource(source))
                val request = HarnessCompilationRequest(HarnessId("reference"), ItemId(kind.name), kind, source)
                val result = host.compile(request)
                val success = assertIs<HarnessCompilationResult.Success>(result, result.toDiagnosticText())
                success.code.close()
                assertEquals(emptyList(), success.warnings.map { it.message })
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private fun HarnessCompilationResult.toDiagnosticText(): String =
    (this as? HarnessCompilationResult.Failure)?.diagnostics?.joinToString("\n") {
        "${it.line}:${it.column} ${it.message}"
    } ?: toString()
