package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import kotlinx.serialization.json.JsonObject

/** Desktop browser tools, independent of provider web search. */
public val BrowserAgentControl: FeatureToggle.Flag =
    FeatureToggle.Flag(
        "browser.agent_control",
        "Управление встроенным браузером агентом",
        default = false,
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

/**
 * Bounded operations on a tab; native objects never cross this API. [Inspect] only observes: it returns the page
 * evidence an independent safety review of the operation named by [BrowserReview.operation] is bound to, and
 * leaves the page, its element references and the last snapshot untouched.
 */
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
    Inspect,
}

/**
 * Binds an agent request to its independent safety review; only the host's tool sets it, tool arguments never carry
 * it. [operation] is the reviewed operation. [approved] is the page evidence the review approved, or null while
 * that evidence is still being collected by [BrowserOperation.Inspect]. A request carrying [approved] runs only
 * while the live tab still shows the same page, target and focus, checked under the tab's operation lock.
 */
public data class BrowserReview(val operation: BrowserOperation, val approved: JsonObject? = null) {
    override fun toString(): String = "BrowserReview(operation=$operation)"
}

/** Correlated request. [owner] is a trusted session identity; null is a user action. */
public data class BrowserRequest(
    val id: String,
    val operation: BrowserOperation,
    val tabId: String? = null,
    val owner: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val review: BrowserReview? = null,
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
