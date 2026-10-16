package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserLoadFailureTest {
    private val failure = BrowserLoadFailure(
        "https://example.com",
        "ERR_CERT_DATE_INVALID",
        isCertificate = true,
        isProceedAllowed = true,
        id = "first",
    )
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
    fun `return to safety is bound to the selected human certificate warning`() {
        val intent = BrowserIntent.Internal.ReturnToSafety("tab", failure)
        BrowserMachineSpec.assertIgnored(state.copy(areErrorPagesEnabled = false), intent)
        BrowserMachineSpec.assertIgnored(state.copy(selectedTabId = "other"), intent)
        BrowserMachineSpec.assertIgnored(state.copy(page = page.copy(failure = failure.copy(id = "next"))), intent)
        BrowserMachineSpec.assertIgnored(
            state.copy(tabs = listOf(BrowserTab("tab", owner = "agent", page = page))),
            intent,
        )
        val network = failure.copy(code = "ERR_NAME_NOT_RESOLVED", isCertificate = false)
        BrowserMachineSpec.assertIgnored(
            state.copy(page = page.copy(failure = network)),
            BrowserIntent.Internal.ReturnToSafety("tab", network),
        )
        BrowserMachineSpec.assertIgnored(
            state.copy(page = page.copy(failure = network)),
            BrowserIntent.Internal.ProceedCertificate("tab", network),
        )
    }

    @Test
    fun `human controlled agent tab decides and anonymous failures never do`() {
        val controlled = state.copy(tabs = listOf(BrowserTab("tab", page, owner = "agent", isHumanControlled = true)))
        BrowserMachineSpec.assertTransition(
            from = controlled,
            intent = BrowserIntent.Internal.ProceedCertificate("tab", failure),
            to = controlled,
            effects = listOf(BrowserEffect.ProceedCertificate("tab", failure)),
        )
        val anonymous = failure.copy(id = "")
        BrowserMachineSpec.assertIgnored(
            state.copy(page = page.copy(failure = anonymous)),
            BrowserIntent.Internal.ReturnToSafety("tab", anonymous),
        )
    }

    @Test
    fun `navigation clears the failure but keeps the committed indicator`() {
        val committed = state.copy(
            page = page.copy(failure = null, security = BrowserSecurity.CertificateException, isBackAvailable = true),
        )
        val expected = committed.page.copy(isLoading = true)
        BrowserMachineSpec.assertTransition(
            from = committed,
            intent = BrowserIntent.Public.Reload,
            to = committed.copy(page = expected),
            effects = listOf(BrowserEffect.Reload),
        )
        BrowserMachineSpec.assertTransition(
            from = committed,
            intent = BrowserIntent.Public.Back,
            to = committed.copy(page = expected),
            effects = listOf(BrowserEffect.Back),
        )
        BrowserMachineSpec.assertTransition(
            from = state,
            intent = BrowserIntent.Public.Open("https://example.org/"),
            to = state.copy(page = page.copy(url = "https://example.org/", isLoading = true, failure = null)),
            effects = listOf(BrowserEffect.Load("https://example.org/")),
        )
    }

    @Test
    fun `revoked availability drops the failure and indicator`() {
        val committed = state.copy(page = page.copy(security = BrowserSecurity.Https))
        BrowserMachineSpec.assertTransition(
            from = committed,
            intent = BrowserIntent.Internal.AvailabilityChanged(false),
            to = committed.copy(
                isEnabled = false,
                isConfigured = true,
                display = BrowserDisplay.Hidden,
                page = page.copy(failure = null, security = BrowserSecurity.Unknown),
            ),
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
    fun `certificate redirect and submission errors never auto retry`() {
        assertFalse(failure.isNetworkRetryable)
        assertFalse(BrowserLoadFailure("https://example.com", "ERR_TOO_MANY_REDIRECTS").isNetworkRetryable)
        assertTrue(BrowserLoadFailure("https://example.com", "ERR_INTERNET_DISCONNECTED").isNetworkRetryable)
        assertFalse(
            BrowserLoadFailure("https://example.com", "ERR_CONNECTION_RESET", isSubmission = true).isNetworkRetryable,
        )
    }
}
