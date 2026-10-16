package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserLoadFailureTest {
    private val failure = BrowserLoadFailure("https://example.com", "ERR_CERT_DATE_INVALID", true, true, id = "first")
    private val page = BrowserPage(url = failure.url, failure = failure)
    private val state = BrowserState.Running(
        isEnabled = true,
        areErrorPagesEnabled = true,
        page = page,
        tabs = listOf(BrowserTab("tab", page = page)),
        selectedTabId = "tab",
    )

    @Test
    fun `only matching human certificate warning can proceed`() {
        val intent = BrowserIntent.Internal.ProceedCertificate("tab", failure)
        BrowserMachineSpec.assertTransition(
            from = state,
            intent = intent,
            to = state,
            effects = listOf(BrowserEffect.ProceedCertificate("tab", failure)),
        )
        BrowserMachineSpec.assertIgnored(state.copy(areErrorPagesEnabled = false), intent)
        BrowserMachineSpec.assertIgnored(state.copy(isEnabled = false), intent)
        BrowserMachineSpec.assertIgnored(state.copy(selectedTabId = "other"), intent)
        BrowserMachineSpec.assertIgnored(state.copy(page = BrowserPage()), intent)
        BrowserMachineSpec.assertIgnored(state.copy(page = page.copy(failure = failure.copy(id = "next"))), intent)
        BrowserMachineSpec.assertIgnored(
            state.copy(tabs = listOf(BrowserTab("tab", owner = "agent", page = page))),
            intent,
        )
        val fatal = failure.copy(isProceedAllowed = false)
        BrowserMachineSpec.assertIgnored(
            state.copy(page = page.copy(failure = fatal)),
            BrowserIntent.Internal.ProceedCertificate("tab", fatal),
        )
    }

    @Test
    fun `fatal warning can return to safety without exception`() {
        val fatal = failure.copy(isProceedAllowed = false)
        val current = state.copy(page = page.copy(failure = fatal))
        BrowserMachineSpec.assertTransition(
            from = current,
            intent = BrowserIntent.Internal.ReturnToSafety("tab", fatal),
            to = current,
            effects = listOf(BrowserEffect.ReturnToSafety("tab", fatal)),
        )
    }

    @Test
    fun `network recovery requires enabled error pages`() {
        BrowserMachineSpec.assertTransition(
            from = state,
            intent = BrowserIntent.Internal.NetworkRestored,
            to = state,
            effects = listOf(BrowserEffect.RetryNetworkErrors),
        )
        BrowserMachineSpec.assertIgnored(
            state.copy(areErrorPagesEnabled = false),
            BrowserIntent.Internal.NetworkRestored,
        )
        BrowserMachineSpec.assertIgnored(state.copy(isEnabled = false), BrowserIntent.Internal.NetworkRestored)
        BrowserMachineSpec.assertTransition(
            from = state,
            intent = BrowserIntent.Internal.ErrorPagesChanged(false),
            to = state.copy(areErrorPagesEnabled = false),
        )
    }

    @Test
    fun `certificate and redirect errors never auto retry`() {
        assertFalse(failure.isNetworkRetryable)
        assertFalse(BrowserLoadFailure("https://example.com", "ERR_TOO_MANY_REDIRECTS").isNetworkRetryable)
        assertTrue(BrowserLoadFailure("https://example.com", "ERR_INTERNET_DISCONNECTED").isNetworkRetryable)
    }
}
