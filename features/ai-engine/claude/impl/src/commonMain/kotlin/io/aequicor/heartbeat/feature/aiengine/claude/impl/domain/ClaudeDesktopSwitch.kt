package io.aequicor.heartbeat.feature.aiengine.claude.impl.domain

import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine

/** Whether the Claude desktop app is offered as a runtime and in engine management ([ClaudeEngine.DesktopApp]). */
internal fun interface ClaudeDesktopSwitch {
    suspend fun isEnabled(): Boolean

    companion object {
        /** Hosts and tests without toggles. */
        val On: ClaudeDesktopSwitch = ClaudeDesktopSwitch { true }
    }
}
