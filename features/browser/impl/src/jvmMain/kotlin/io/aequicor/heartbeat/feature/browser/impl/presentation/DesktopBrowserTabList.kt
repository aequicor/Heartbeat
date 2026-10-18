package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The tabs a request may see: an agent session its own group, the user all of them. */
internal fun Collection<DesktopBrowserEntry>.listTabs(request: BrowserRequest): BrowserResult {
    val visible = filter { request.owner == null || it.tab.owner == request.owner }
    return BrowserResult(
        request.id,
        buildJsonObject {
            put(
                "tabs",
                JsonArray(
                    visible.map { entry ->
                        buildJsonObject {
                            put("id", entry.tab.id)
                            put("url", entry.tab.page.url)
                            put("title", entry.tab.page.title)
                            put("humanControlled", entry.tab.isHumanControlled)
                        }
                    },
                ),
            )
        },
    )
}
