package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import kotlinx.coroutines.flow.Flow

/** Availability is read through a port; domain does not know the toggle implementation. */
internal fun interface BrowserAvailability {
    fun observe(): Flow<Boolean>
}

/** Commands and current page of the retained native browsing session. */
internal interface BrowserSession {
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
