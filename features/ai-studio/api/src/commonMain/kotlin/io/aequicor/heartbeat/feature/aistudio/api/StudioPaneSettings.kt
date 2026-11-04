package io.aequicor.heartbeat.feature.aistudio.api

/** Exact local new-page parameters; existing conversations never inherit another page's effort seed. */
public fun AiStudioState.Ready.settingsForPane(paneId: Int?): RunSettings {
    val pane = panes.firstOrNull { it.id == (paneId ?: focusedPaneId) }
    if (pane?.sessionId == null) return pane?.settings ?: settings
    return settingsForSession(pane.sessionId)
}

/** Existing-chat fallback never imports a first-turn override from a new page. */
internal fun AiStudioState.Ready.settingsForSession(sessionId: String): RunSettings {
    val confirmed = configurations[sessionId]?.applied
    return settings.copy(
        modelId = confirmed?.modelId ?: settings.modelId,
        approval = confirmed?.approval ?: settings.approval,
        nativeEffort = null,
    )
}
