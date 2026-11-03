package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.StudioEffortOverride
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/** Local first-turn choices, including explicit native default, win over later profile effort observations. */
internal fun StudioEffortOverride?.applyTo(choices: ImmutableMap<String, String>): ImmutableMap<String, String> {
    if (this == null) return choices
    val current = persistentMapOf<String, String>().putAll(choices)
    val selected = effort
    return if (selected == null) current.remove(modelId) else current.put(modelId, selected)
}
