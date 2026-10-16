package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import kotlinx.serialization.Serializable

/** Durable metadata only: native history, pending approvals and agent permissions never survive restart. */
@Serializable
internal data class SavedBrowserWorkspace(
    val tabs: List<SavedBrowserTab> = emptyList(),
    val selectedTabId: String? = null,
) {
    /** Ignore unusable records without navigating to unsupported schemes or creating duplicate identities. */
    fun validated(): SavedBrowserWorkspace {
        val valid = tabs.asSequence().filter { it.id.isNotBlank() && (it.url.isEmpty() || isBrowserUrlAllowed(it.url)) }
            .distinctBy { it.id }.take(MAX_BROWSER_TABS)
            .map { tab -> tab.takeIf { it.faviconUrl.isEmpty() || isFaviconValid(it) } ?: tab.copy(faviconUrl = "") }
            .toList()
        return copy(
            tabs = valid,
            selectedTabId = selectedTabId?.takeIf { id -> valid.any { it.id == id } } ?: valid.firstOrNull()?.id,
        )
    }
}

/** The owner preserves the session group; the tab is always restored under human control. */
@Serializable
internal data class SavedBrowserTab(
    val id: String,
    val url: String = "",
    val title: String = "",
    val owner: String? = null,
    val faviconUrl: String = "",
)

private fun isFaviconValid(tab: SavedBrowserTab): Boolean =
    tab.url.isNotEmpty() && tab.faviconUrl.length <= MAX_FAVICON_URL_LENGTH && isBrowserUrlAllowed(tab.faviconUrl)

/** Profile-owned, main-safe journal. Writes finish before user operations report completion. */
internal interface BrowserWorkspaceStorage {
    suspend fun load(): SavedBrowserWorkspace
    suspend fun save(workspace: SavedBrowserWorkspace)
}

/** Shared bound for live and restored desktop tabs. */
internal const val MAX_BROWSER_TABS = 20
