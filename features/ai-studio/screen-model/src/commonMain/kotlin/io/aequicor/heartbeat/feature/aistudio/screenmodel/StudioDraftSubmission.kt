package io.aequicor.heartbeat.feature.aistudio.screenmodel

/** Effective native selection shared by transcript projection and submission; missing children fall back to root. */
fun AiStudioScreenState.selectedNative(sessionId: String?): String = subSessions[sessionId]?.takeIf { key ->
    nativeTrees[sessionId]?.sessions?.any { it.key == key && it.kind == SubSessionKindUi.Agent } == true
} ?: PRIMARY_SUB_SESSION

/** The chat a `/compact` draft of pane [paneId] compacts; none while the pane shows a native sub-agent of it. */
fun AiStudioScreenState.compactedChat(paneId: Int): String? =
    panes.firstOrNull { it.id == paneId }?.sessionId?.takeIf { selectedNative(it) == PRIMARY_SUB_SESSION }
