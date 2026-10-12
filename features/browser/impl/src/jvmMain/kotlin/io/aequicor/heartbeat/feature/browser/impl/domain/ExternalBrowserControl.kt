package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** What an agent does in the user's external browser through the `ex_control_*` tools. */
internal enum class ExternalOperation {
    Tabs,
    NewTab,
    CloseTab,
    RequestTab,
    Release,
    Open,
    Read,
    Screenshot,
    Click,
    Type,
    Key,
    Scroll,
    Drag,
}

/** Agent operations on the external browser; [owner] comes from the trusted session, never from arguments. */
internal interface ExternalBrowserControl {
    /** Runs [operation] for [owner]; failures come back as [BrowserResult.error]. */
    suspend fun execute(owner: String, operation: ExternalOperation, arguments: JsonObject): BrowserResult
}

/** Whether agents may drive the external browser at all (rollout toggles). */
internal interface ExternalBrowserAgentPolicy {
    /** Current permission and its changes. */
    val availability: Flow<Boolean>
}
