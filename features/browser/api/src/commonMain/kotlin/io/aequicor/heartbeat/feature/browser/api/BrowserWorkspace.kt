package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import kotlinx.serialization.json.JsonObject

/** Desktop browser tools, independent of provider web search. */
public val BrowserAgentControl: FeatureToggle.Flag =
    FeatureToggle.Flag(
        "browser.agent_control",
        "Управление встроенным браузером агентом",
        default = true,
    )

/** Human-assisted reading of research sources through the browser session. */
public val BrowserResearchAccess: FeatureToggle.Flag =
    FeatureToggle.Flag(
        "browser.research_access",
        "Загрузка источников через встроенный браузер",
        default = false,
    )

/** Display mode does not own native browser lifetime. */
public enum class BrowserDisplay {
    Hidden,
    Panel,
    Expanded,
}

/**
 * A tab's owner is supplied by the host, never by tool arguments. Null denotes a human-owned tab.
 */
public data class BrowserTab(
    val id: String,
    val page: BrowserPage = BrowserPage(),
    val owner: String? = null,
    val isHumanControlled: Boolean = false,
    val pendingOwner: String? = null,
    val controlRequestId: String? = null,
)

/** Bounded operations on a tab; native objects never cross this API. */
public enum class BrowserOperation {
    ListTabs,
    NewTab,
    CloseTab,
    Navigate,
    Search,
    Read,
    Screenshot,
    Click,
    Type,
    Key,
    Scroll,
    Drag,
    RequestControl,
    ApproveControl,
    RejectControl,
    TakeControl,
    ReturnControl,
    CaptureResource,
    ClearData,
}

/** Correlated request. [owner] is a trusted session identity; null is a user action. */
public data class BrowserRequest(
    val id: String,
    val operation: BrowserOperation,
    val tabId: String? = null,
    val owner: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
) {
    override fun toString(): String = "BrowserRequest(operation=$operation)"
}

/** Result delivered only to the operation subscriber, never retained in machine state. */
public data class BrowserResult(
    val requestId: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val error: String? = null,
    val imageBase64: String? = null,
) {
    override fun toString(): String = "BrowserResult(failed=${error != null})"
}
