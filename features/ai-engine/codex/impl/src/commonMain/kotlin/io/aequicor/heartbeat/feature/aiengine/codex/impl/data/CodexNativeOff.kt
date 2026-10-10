package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The only native execution overrides supported by this adapter. An absent restriction preserves the captured
 * launch configuration; it never writes an On value. Both process flags and cold-resume config carry restrictions.
 */
internal data class CodexNativeOff(val isShellDisabled: Boolean = false, val isSearchDisabled: Boolean = false) {
    val isRestricted: Boolean get() = isShellDisabled || isSearchDisabled

    /** Appended after every user override and isolation flag without accepting arbitrary configuration keys. */
    fun arguments(): List<String> = buildList {
        if (isShellDisabled) addAll(listOf("-c", "features.shell_tool=false"))
        if (isSearchDisabled) addAll(listOf("-c", "web_search=\"disabled\""))
    }

    /**
     * 0.160.0 is the first verified stable protocol. Newer stable versions also require effective-config checks;
     * unknown and prerelease versions cannot authorize restrictions. Unrestricted launches retain compatibility.
     */
    fun requireVersion(version: String?) {
        if (!isRestricted) return
        val parts = version?.takeIf { StableVersion.matches(it) }?.split('.') ?: unsupportedPolicy()
        val numbers = parts.map { it.toIntOrNull() ?: unsupportedPolicy() }
        if (numbers[0] == 0 && numbers[1] < MIN_MINOR) unsupportedPolicy()
    }

    /** Applies Off after adapter defaults, including the optional `web_search=live` default. */
    fun applyTo(config: JsonObject): JsonObject = buildJsonObject {
        config.forEach { (key, value) -> put(key, value) }
        if (isShellDisabled) {
            put(
                "features",
                buildJsonObject {
                    (config["features"] as? JsonObject).orEmpty().forEach { (key, value) -> put(key, value) }
                    put("shell_tool", false)
                },
            )
        }
        if (isSearchDisabled) put("web_search", "disabled")
    }

    /**
     * Audits the candidate execution process's `config/read(includeLayers=true)` response before thread creation
     * or resume. Managed settings must not override Off, and active sessionFlags must retain the requested keys.
     * Nothing from the configuration or its layers is logged: they may contain credentials.
     */
    fun validateConfig(response: JsonObject) {
        if (!isRestricted) return
        val effective = response["config"] as? JsonObject ?: unsupportedPolicy()
        val layers = response["layers"] as? JsonArray ?: unsupportedPolicy()
        val hasFlags = layers.any { value ->
            val layer = value as? JsonObject
            val name = layer?.get("name") as? JsonObject
            val config = layer?.get("config") as? JsonObject
            // 0.160.0 omits this nullable field for active layers despite its generated TS declaration.
            val isActive = layer?.get("disabledReason").let { it == null || it == JsonNull }
            name?.text("type") == "sessionFlags" && isActive &&
                config != null && matches(config)
        }
        if (!matches(effective) || !hasFlags) unsupportedPolicy()
    }

    private fun matches(config: JsonObject): Boolean =
        (!isShellDisabled || (config["features"] as? JsonObject)?.get("shell_tool") == JsonPrimitive(false)) &&
            (!isSearchDisabled || config["web_search"] == JsonPrimitive("disabled"))

    private fun unsupportedPolicy(): Nothing = fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))

    private companion object {
        val StableVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        const val MIN_MINOR = 160
    }
}
