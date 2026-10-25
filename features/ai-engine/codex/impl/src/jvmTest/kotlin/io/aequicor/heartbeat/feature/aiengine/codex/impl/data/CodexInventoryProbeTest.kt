package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

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

    @Test
    fun `unset optional features are omitted while explicit feature values are preserved`() {
        val features = json(
            "network_proxy" to JsonNull,
            "shell_tool" to JsonPrimitive(false),
            "auth_elicitation" to JsonPrimitive(true),
        )
        assertEquals(
            listOf("-c", "features.shell_tool=false", "-c", "features.auth_elicitation=true"),
            codexInventoryFeatureArguments(features),
        )
        assertEquals(emptyList(), codexInventoryFeatureArguments(json("network_proxy" to JsonNull)))
    }

    @Test
    fun `unsupported feature types and paths cannot become probe overrides`() {
        for (features in listOf(
            json("shell_tool" to "false".json()),
            json("shell_tool" to JsonPrimitive(0)),
            json("shell_tool" to json()),
            json("shell_tool.extra" to JsonPrimitive(false)),
            json("shell_tool=false" to JsonPrimitive(false)),
        )) {
            assertFailsWith<EngineException> { codexInventoryFeatureArguments(features) }
        }
    }

    @Test
    fun `unset flags cannot satisfy required native restrictions`() {
        val off = CodexNativeOff(isToolFree = true)
        val locked = off.applyTo(json())
        val features = JsonObject(locked.obj("features") + ("shell_tool" to JsonNull))
        val unset = JsonObject(locked + ("features" to features))
        for ((effective, flags) in listOf(unset to locked, locked to unset)) {
            val layer = json("name" to json("type" to "sessionFlags".json()), "config" to flags)
            val response = json("config" to effective, "layers" to JsonArray(listOf(layer)))
            assertFailsWith<EngineException> { off.validateConfig(response) }
        }
    }

    /** Explicit opt-in exercises the production probe on a real CLI, without credentials or model traffic. */
    @Test
    fun `installed CLI inventory uses the selected model without reading its version`() {
        val executable = System.getenv("HEARTBEAT_CODEX_INVENTORY_EXECUTABLE") ?: return
        withDispatchers { dispatchers ->
            val path = CodexModelCatalog.create(executable, dispatchers).use { catalog ->
                assertFailsWith<EngineException> {
                    val off = CodexNativeOff(isToolFree = true)
                    CodexInventoryProbe(dispatchers, catalog).verify(executable, "old identity", target(off), off)
                }
                assertFailsWith<EngineException> { catalog.requireModel("unknown-model") }
                for (off in listOf(
                    CodexNativeOff(isActionAnalysisRequired = true),
                    CodexNativeOff(isToolFree = true),
                )) {
                    val identity = codexExecutableIdentity(executable)
                    CodexInventoryProbe(dispatchers, catalog).verify(executable, identity, target(off), off)
                    // The working thread's real hosted declarations replace the inert marker.
                    CodexInventoryProbe(dispatchers, catalog).verify(
                        executable,
                        identity,
                        target(off).copy(tools = searchToolSpecs().map { it as JsonObject }),
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
                assertFailsWith<EngineException> {
                    CodexInventoryProbe(dispatchers, catalog).verify(
                        executable,
                        codexExecutableIdentity(executable),
                        target(off),
                        off,
                    )
                }
                catalog.path
            }
            assertFalse(Files.exists(path.parent))
        }
    }

    /** Uses config/read from the real working process before probing and creating a native thread. */
    @Test
    fun `protected production launch creates a thread with the actual effective feature configuration`() {
        val executable = System.getenv("HEARTBEAT_CODEX_INVENTORY_EXECUTABLE") ?: return
        val directory = Files.createTempDirectory("heartbeat-codex-launch-test-")
        try {
            withDispatchers { dispatchers ->
                coroutineScope {
                    val transport = LocalCodexTransport(
                        CodexLocalConfiguration(executable = executable, homeDirectory = directory.toString()),
                        EngineLaunchConfig.Default,
                        dispatchers,
                        FakeScope(this),
                    )
                    for (off in listOf(
                        CodexNativeOff(isActionAnalysisRequired = true),
                        CodexNativeOff(isToolFree = true),
                    )) {
                        val rpc = CodexRpc(transport.prepare().open(off), this)
                        val events = launch {
                            rpc.notifications.collect { message ->
                                message["id"]?.let { id -> rpc.reject(id) }
                            }
                        }
                        try {
                            rpc.initialize(experimentalApi = true)
                            val isolation = codexIsolationConfig(
                                rpc,
                                directory.toString(),
                                CodexIsolationSettings(
                                    isSearchEnabled = false,
                                    areQuestionsEnabled = false,
                                    off = off,
                                    model = "gpt-6.1-sol",
                                ),
                            )
                            val response = rpc.request(
                                "thread/start",
                                json(
                                    "model" to "gpt-6.1-sol".json(),
                                    "cwd" to directory.toString().json(),
                                    "sandbox" to "read-only".json(),
                                    "approvalPolicy" to "never".json(),
                                    "ephemeral" to JsonPrimitive(true),
                                    "config" to isolation,
                                ),
                            )
                            assertNotNull(response.obj("thread").text("id"))
                        } finally {
                            events.cancel()
                            rpc.close()
                        }
                    }
                }
            }
        } finally {
            Files.walk(
                directory,
            ).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /** The effective config and thread config a working process with only adapter defaults would produce. */
    private fun target(off: CodexNativeOff): CodexInventoryTarget {
        val base = json("features" to JsonObject(CodexDisabledCapabilities.associateWith { JsonPrimitive(false) }))
        val effective = off.applyTo(base)
        return CodexInventoryTarget("gpt-6.1-sol", effective, effective, emptyList())
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
