package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Behaviors of [FakeCodexInventoryCli] selected by the wrapper script. */
internal enum class FakeInventoryScenario {
    /** Declares the dynamic tools exactly as received. */
    Faithful,

    /** Replaces the first dynamic tool with a same-named native tool of another description. */
    Replaced,

    /** Answers thread/start without a thread id. */
    MalformedThread,
}

/**
 * A credential-free stand-in for `codex` used by the probe tests: `debug models --bundled` prints one model, and
 * `app-server` answers the probe's JSON-RPC, records the thread/start params and posts the dynamic tools to the
 * loopback provider like a Responses request. It runs as a separate JVM because the probe spawns a process.
 */
internal object FakeCodexInventoryCli {
    const val MODEL = "fake-model"

    /** POSIX wrapper that launches this fake with the test classpath; the probe clears the environment. */
    fun install(directory: File, scenario: FakeInventoryScenario, record: File): File {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        return File(directory, "codex-${scenario.name}").apply {
            writeText(
                "#!/bin/sh\nexec '$java' -cp '$classpath' ${FakeCodexInventoryCli::class.java.name}Kt " +
                    "'${scenario.name}' '${record.absolutePath}' \"$@\"\n",
            )
            check(setExecutable(true))
        }
    }

    fun run(scenario: FakeInventoryScenario, record: Path, args: List<String>) {
        // The JSON-RPC wire is this process's standard output, not a log.
        val output = FileOutputStream(FileDescriptor.out).bufferedWriter()
        if (args.firstOrNull() == "debug") {
            output.write("""{"models":[{"slug":"$MODEL"}]}""")
            output.newLine()
            output.flush()
            return
        }
        val overrides = args.zipWithNext().filter { it.first == "-c" }.map {
            it.second.substringBefore('=') to it.second.substringAfter('=')
        }
        val config = effectiveConfig(overrides)
        val baseUrl = overrides.last { it.first == "model_providers.heartbeat_inventory" }.second
            .substringAfter("base_url=\"").substringBefore('"')
        var tools: List<JsonObject> = emptyList()
        System.`in`.bufferedReader().lineSequence().forEach { line ->
            val message = Json.parseToJsonElement(line) as JsonObject
            val id = message["id"] ?: return@forEach
            val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
            val result = when (message.text("method")) {
                "config/read" -> json(
                    "config" to config,
                    "layers" to JsonArray(
                        listOf(json("name" to json("type" to "sessionFlags".json()), "config" to config)),
                    ),
                )

                "thread/start" -> {
                    Files.writeString(record, params.toString())
                    tools = (params["dynamicTools"] as JsonArray).map { it as JsonObject }
                    if (scenario == FakeInventoryScenario.MalformedThread) {
                        json("thread" to json())
                    } else {
                        json("thread" to json("id" to "thread".json()))
                    }
                }

                "turn/start" -> {
                    post(baseUrl, responsesRequest(tools, scenario))
                    json()
                }

                else -> json()
            }
            output.write(json("id" to id, "result" to result).toString())
            output.newLine()
            output.flush()
        }
    }

    /** A default of the CLI build that is outside the inventory allowlist, as 0.163 reports allow_login_shell. */
    const val DEFAULT_KEY = "allow_login_shell"

    private fun effectiveConfig(overrides: List<Pair<String, String>>): JsonObject = buildJsonObject {
        put(DEFAULT_KEY, true)
        put(
            "features",
            JsonObject(
                overrides.filter { it.first.startsWith("features.") }
                    .associate { it.first.removePrefix("features.") to JsonPrimitive(it.second.toBoolean()) },
            ),
        )
        overrides.lastOrNull { it.first == "web_search" }?.let { put("web_search", it.second.trim('"')) }
        if (overrides.any { it.first == "agents.enabled" }) put("agents", json("enabled" to JsonPrimitive(false)))
        overrides.lastOrNull { it.first == "model_catalog_json" }?.let {
            put("model_catalog_json", Json.parseToJsonElement(it.second))
        }
    }

    private fun responsesRequest(tools: List<JsonObject>, scenario: FakeInventoryScenario): JsonObject {
        val functions: List<JsonElement> = tools.mapIndexed { index, tool ->
            val description = if (scenario == FakeInventoryScenario.Replaced && index == 0) {
                "Native replacement".json()
            } else {
                tool.getValue("description")
            }
            json(
                "type" to "function".json(),
                "name" to tool.getValue("name"),
                "description" to description,
                "strict" to JsonPrimitive(false),
                "parameters" to tool.getValue("inputSchema"),
            )
        }
        return json("tools" to JsonArray(functions), "input" to JsonArray(emptyList()))
    }

    private fun post(baseUrl: String, body: JsonObject) {
        val bytes = body.toString().toByteArray()
        val connection = URI("$baseUrl/responses").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(bytes) }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

/** Removes a temporary test directory tree. */
internal fun deleteTree(root: Path) {
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

fun main(args: Array<String>) {
    FakeCodexInventoryCli.run(FakeInventoryScenario.valueOf(args[0]), Path.of(args[1]), args.drop(2))
}
