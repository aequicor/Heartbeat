package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Availability is read through a port; domain does not know the toggle implementation. */
internal fun interface BrowserAvailability {
    fun observe(): Flow<Boolean>
}

/** Commands and current page of the retained native browsing session. */
internal interface BrowserSession {
    val tabs: Flow<Pair<List<BrowserTab>, String?>>
        get() = flowOf(emptyList<BrowserTab>() to null)

    suspend fun perform(request: BrowserRequest): BrowserResult =
        BrowserResult(request.id, error = "Desktop browser required")

    suspend fun selectTab(id: String): Unit = Unit

    suspend fun cancelOperation(id: String): Unit = Unit

    val pages: Flow<BrowserPage>
    suspend fun setEnabled(isEnabled: Boolean)
    suspend fun execute(command: BrowserCommand)
}

/** Domain commands are mapped to platform controller commands by presentation. */
internal sealed interface BrowserCommand {
    data class Load(val url: String) : BrowserCommand
    data object Back : BrowserCommand
    data object Forward : BrowserCommand
    data object Reload : BrowserCommand
    data object Stop : BrowserCommand
}

/** Agent control policy is observed for revocation and rechecked after queued operations. */
internal interface BrowserAgentPolicy {
    fun observe(): Flow<Boolean>

    suspend fun isEnabled(): Boolean
}
