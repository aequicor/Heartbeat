package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** What an agent does in the user's external browser through the `ex_control_*` tools. */
internal enum class ExternalOperation {
    Tabs,
    ActiveTab,
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

    /** The address [owner]'s tab [tabId] shows, for approval texts; `null` for a tab it does not control. */
    suspend fun address(owner: String, tabId: String): String?
}

/** Arguments the tools add for a hand-over the user approved; the model's own values are always replaced. */
internal object ApprovedTab {
    const val ID = "approvedTabId"
    const val URL = "approvedUrl"
}

/** Whether agents may drive the external browser at all (rollout toggles). */
internal interface ExternalBrowserAgentPolicy {
    /** Current permission and its changes. */
    val availability: Flow<Boolean>
}
