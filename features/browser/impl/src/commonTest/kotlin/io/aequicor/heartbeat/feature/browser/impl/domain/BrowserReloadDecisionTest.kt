package io.aequicor.heartbeat.feature.browser.impl.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserReloadDecisionTest {
    @Test
    fun `reload retries B after A commits and B fails provisionally`() {
        assertEquals(
            BrowserReloadDecision.LoadRequested("https://example.com/b"),
            browserReloadDecision(
                requestedAddress = "https://example.com/b",
                committedAddress = "https://example.com/a",
            ),
        )
    }

    @Test
    fun `reload retries the last redirect when it never committed`() {
        assertEquals(
            BrowserReloadDecision.LoadRequested("https://example.com/redirected"),
            browserReloadDecision(
                requestedAddress = "https://example.com/redirected",
                committedAddress = "https://example.com/a",
            ),
        )
    }

    @Test
    fun `failure before any commit loads the requested address`() {
        assertEquals(
            BrowserReloadDecision.LoadRequested("https://example.com/b"),
            browserReloadDecision(requestedAddress = "https://example.com/b", committedAddress = ""),
        )
    }

    @Test
    fun `a committed page keeps native reload semantics including HTTP errors`() {
        assertEquals(
            BrowserReloadDecision.ReloadCommitted,
            browserReloadDecision("https://example.com/a", "https://example.com/a"),
        )
    }

    @Test
    fun `reload without a requested document is ignored`() {
        assertEquals(BrowserReloadDecision.Ignore, browserReloadDecision("", ""))
    }
}
