package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.ObservedValue
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsLayer

/**
 * Layers exactly as Heartbeat observed them for one adapter call. [profile] holds the values Heartbeat writes into
 * the engine's configuration directory at launch; [project] holds parsed values of a saved project only after its
 * trust decision allowed reading it. Values may name the user's paths and servers, contain vault references and are
 * never logged.
 */
public data class EffectiveSettingsRequest(
    val profile: EngineConfigValues,
    /** Parsed project values, or null when the project was not read (no workspace, trust, unreadable, unavailable). */
    val project: EngineConfigValues? = null,
) {
    override fun toString(): String = "EffectiveSettingsRequest(project=${project != null})"
}

/**
 * What an adapter can prove about its engine's own effective settings for the documented surfaces, by surface id
 * ([io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField.id]).
 */
public data class EngineEffectiveSettings(
    /** Engine-layer values; a surface absent here is not claimed by the adapter. */
    val values: Map<String, ObservedValue> = emptyMap(),
    /** Resolutions the adapter itself declares; a surface absent here stays unknown to Heartbeat. */
    val merged: Map<String, EngineDeclaredResolution> = emptyMap(),
) {
    override fun toString(): String = "EngineEffectiveSettings(values=${values.size}, merged=${merged.size})"
}

/** One resolution the adapter itself declares, with the layers it accounts for. */
public data class EngineDeclaredResolution(val value: ObservedValue, val layers: Set<SettingsLayer>) {
    init {
        require(layers.isNotEmpty()) { "A declared resolution names its layers" }
    }

    override fun toString(): String = "EngineDeclaredResolution(layers=$layers)"
}
