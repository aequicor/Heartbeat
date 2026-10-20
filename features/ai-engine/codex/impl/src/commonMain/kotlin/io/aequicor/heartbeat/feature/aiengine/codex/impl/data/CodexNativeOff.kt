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
internal data class CodexNativeOff(
    val isShellDisabled: Boolean = false,
    val isSearchDisabled: Boolean = false,
    val isVirtualWorkspace: Boolean = false,
) {
    val isRestricted: Boolean get() = isShellDisabled || isSearchDisabled || isVirtualWorkspace

    /** Appended after every user override and isolation flag without accepting arbitrary configuration keys. */
    fun arguments(): List<String> = buildList {
        if (isShellDisabled) executionFeatures.forEach { addAll(listOf("-c", "features.$it=false")) }
        if (isSearchDisabled) addAll(listOf("-c", "web_search=\"disabled\""))
        if (isVirtualWorkspace) virtualFeatures.forEach { addAll(listOf("-c", "features.$it=false")) }
    }

    /**
     * 0.160.0 is the first verified protocol. Later versions, including desktop prereleases, are candidates only:
     * effective-config checks must still prove Off before thread creation or resume. Prereleases of the minimum
     * version and unknown versions cannot authorize restrictions. Unrestricted launches retain compatibility.
     */
    fun requireVersion(version: String?) {
        if (!isRestricted) return
        val match = version?.let { Version.matchEntire(it) } ?: unsupportedPolicy()
        val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: unsupportedPolicy() }
        if (numbers[0] == 0 && numbers[1] < MIN_MINOR) unsupportedPolicy()
        if (numbers == listOf(0, MIN_MINOR, 0) && match.groupValues[PRERELEASE_GROUP].isNotEmpty()) unsupportedPolicy()
    }

    /** Applies Off after adapter defaults, including the optional `web_search=live` default. */
    fun applyTo(config: JsonObject): JsonObject = buildJsonObject {
        config.forEach { (key, value) -> put(key, value) }
        if (isShellDisabled || isVirtualWorkspace) {
            put(
                "features",
                buildJsonObject {
                    (config["features"] as? JsonObject).orEmpty().forEach { (key, value) -> put(key, value) }
                    if (isShellDisabled) executionFeatures.forEach { put(it, false) }
                    if (isVirtualWorkspace) virtualFeatures.forEach { put(it, false) }
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

    private fun matches(config: JsonObject): Boolean = (
        !isShellDisabled || executionFeatures.all {
            (config["features"] as? JsonObject)?.get(it) == JsonPrimitive(false)
        }
    ) &&
        (!isSearchDisabled || config["web_search"] == JsonPrimitive("disabled")) &&
        (
            !isVirtualWorkspace || virtualFeatures.all {
                (config["features"] as? JsonObject)?.get(
                    it,
                ) == JsonPrimitive(false)
            }
        )

    private fun unsupportedPolicy(): Nothing = fail(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))

    private companion object {
        val Version = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)(-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
        const val MIN_MINOR = 160
        const val PRERELEASE_GROUP = 4
        val executionFeatures = listOf("shell_tool", "apply_patch_freeform", "multi_agent")
        val virtualFeatures = listOf("view_image_tool", "apply_patch_freeform", "multi_agent")
    }
}
