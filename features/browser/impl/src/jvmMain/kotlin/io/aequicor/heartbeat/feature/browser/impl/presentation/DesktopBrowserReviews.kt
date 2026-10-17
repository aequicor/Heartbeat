package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserReview
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserStorage
import io.aequicor.heartbeat.feature.browser.impl.domain.reviewedBrowserState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The evidence an agent action's independent safety review is bound to: this tab, its page, and the target and
 * focus of the reviewed [BrowserReview.operation]. It observes only; the agent's own read stays valid.
 */
internal suspend fun DesktopBrowserEntry.inspect(request: BrowserRequest): BrowserResult {
    require(request.owner != null) { "Only an agent session is reviewed" }
    val review = requireNotNull(request.review) { "Review operation required" }
    return BrowserResult(request.id, observe(review, request))
}

/**
 * A reviewed agent action runs only on the page, target and focus its review saw. The live tab is observed again
 * under the tab's operation lock, immediately before the action, so a page that changed meanwhile fails it instead
 * of receiving input the reviewer never judged.
 */
internal suspend fun DesktopBrowserEntry.requireReviewedState(request: BrowserRequest) {
    val review = request.review ?: return
    val approved = review.approved ?: return
    check(request.owner != null) { "Only an agent session is reviewed" }
    check(reviewedBrowserState(observe(review, request)) == reviewedBrowserState(approved)) { REVIEWED_STATE_CHANGED }
}

private suspend fun DesktopBrowserEntry.observe(review: BrowserReview, request: BrowserRequest): JsonObject {
    val page = surface.pages.value
    return buildJsonObject {
        put("tabId", tab.id)
        put("url", page.url)
        put("title", page.title.take(MAX_REVIEW_TITLE))
        put("storage", if (storage is BrowserStorage.User) "user" else "agent")
        put("page", driver.safetySnapshot(review.operation, request.arguments))
    }
}

private const val MAX_REVIEW_TITLE = 512
private const val REVIEWED_STATE_CHANGED =
    "The browser action changed after safety review; read the page and request it again"
