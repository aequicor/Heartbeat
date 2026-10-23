package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CodexInventoryProbeTest {
    @Test
    fun `executable replacement invalidates its identity without a version change`() {
        val executable = Files.createTempFile("codex-identity", ".bin")
        try {
            Files.writeString(executable, "first")
            val before = codexExecutableIdentity(executable.toString())
            Files.writeString(executable, "replacement")
            assertNotEquals(before, codexExecutableIdentity(executable.toString()))
        } finally {
            Files.delete(executable)
        }
    }

    /** Explicit opt-in exercises the production probe on a real CLI, without credentials or model traffic. */
    @Test
    fun `installed CLI inventory uses the selected model without reading its version`() {
        val executable = System.getenv("HEARTBEAT_CODEX_INVENTORY_EXECUTABLE") ?: return
        withDispatchers { dispatchers ->
            val path = CodexModelCatalog.create(executable, dispatchers).use { catalog ->
                assertFailsWith<EngineException> {
                    CodexInventoryProbe(
                        dispatchers,
                        catalog,
                    ).verify(executable, "old identity", "gpt-6.1-sol", json(), CodexNativeOff(isToolFree = true))
                }
                assertFailsWith<EngineException> { catalog.requireModel("unknown-model") }
                for (off in listOf(
                    CodexNativeOff(isActionAnalysisRequired = true),
                    CodexNativeOff(isToolFree = true),
                )) {
                    val base = json(
                        "features" to JsonObject(CodexDisabledCapabilities.associateWith { JsonPrimitive(false) }),
                    )
                    CodexInventoryProbe(dispatchers, catalog).verify(
                        executable,
                        codexExecutableIdentity(executable),
                        "gpt-6.1-sol",
                        off.applyTo(base).obj("features"),
                        off,
                    )
                }
                assertFailsWith<EngineException> { catalog.requireConfig(json("model_catalog_json" to "other".json())) }
                // Reintroduce the metadata override that used to defeat false feature flags on GPT-6.1 Sol.
                val source = Json.parseToJsonElement(Files.readString(catalog.path)) as JsonObject
                val models = (source["models"] as JsonArray).map { entry ->
                    val model = entry as JsonObject
                    JsonObject(model + ("tool_mode" to "code_mode_only".json()))
                }
                Files.writeString(catalog.path, JsonObject(source + ("models" to JsonArray(models))).toString())
                val off = CodexNativeOff(isToolFree = true)
                val base = json(
                    "features" to JsonObject(CodexDisabledCapabilities.associateWith { JsonPrimitive(false) }),
                )
                assertFailsWith<EngineException> {
                    CodexInventoryProbe(dispatchers, catalog).verify(
                        executable,
                        codexExecutableIdentity(executable),
                        "gpt-6.1-sol",
                        off.applyTo(base).obj("features"),
                        off,
                    )
                }
                catalog.path
            }
            assertFalse(Files.exists(path.parent))
        }
    }

    private fun withDispatchers(block: suspend (DispatcherProvider) -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            val dispatchers = object : DispatcherProvider {
                override val main = main
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
            }
            runBlocking(main) { block(dispatchers) }
        }
    }
}
