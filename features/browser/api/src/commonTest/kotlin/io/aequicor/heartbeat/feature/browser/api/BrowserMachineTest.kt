package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertFalse

class BrowserMachineTest {
    private val ready = BrowserState.Running(isConfigured = true, isEnabled = true)

    @Test
    fun `start observes without loading any website and duplicate start is ignored`() {
        assertFalse(BrowserEnabled.default)
        BrowserMachineSpec.assertTransition(
            BrowserState.Idle,
            BrowserIntent.Public.Start,
            BrowserState.Running(),
            listOf(BrowserEffect.Observe),
        )
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Public.Start)
    }

    @Test
    fun `toggle enables an empty browser without navigation`() {
        BrowserMachineSpec.assertTransition(
            BrowserState.Running(),
            BrowserIntent.Internal.AvailabilityChanged(true),
            ready,
        )
    }

    @Test
    fun `disabling clears loading and surface history but retains the address`() {
        val from = ready.copy(page = BrowserPage("https://example.com", isLoading = true, isBackAvailable = true))
        val disabled = from.copy(isEnabled = false, page = from.page.copy(isLoading = false, isBackAvailable = false))
        BrowserMachineSpec.assertTransition(from, BrowserIntent.Internal.AvailabilityChanged(false), disabled)
        BrowserMachineSpec.assertTransition(
            disabled,
            BrowserIntent.Internal.AvailabilityChanged(true),
            disabled.copy(isEnabled = true),
        )
    }

    @Test
    fun `disabled and unstarted browsers ignore every navigation action`() {
        val actions = listOf(
            BrowserIntent.Public.Open("example.com"),
            BrowserIntent.Public.Back,
            BrowserIntent.Public.Forward,
            BrowserIntent.Public.Reload,
            BrowserIntent.Public.Stop,
        )
        actions.forEach {
            BrowserMachineSpec.assertIgnored(BrowserState.Idle, it)
            BrowserMachineSpec.assertIgnored(ready.copy(isEnabled = false), it)
        }
    }

    @Test
    fun `opening valid input loads its normalized URL`() {
        BrowserMachineSpec.assertTransition(
            ready,
            BrowserIntent.Public.Open("example.com"),
            ready.copy(page = BrowserPage("https://example.com", isLoading = true)),
            listOf(BrowserEffect.Load("https://example.com")),
        )
    }

    @Test
    fun `invalid input produces an inline error without replacing the current page`() {
        val from = ready.copy(page = BrowserPage("https://example.com"))
        BrowserMachineSpec.assertTransition(
            from,
            BrowserIntent.Public.Open("javascript:alert(1)"),
            from.copy(page = from.page.copy(error = BrowserError.InvalidAddress)),
        )
    }

    @Test
    fun `history commands are guarded by native capabilities`() {
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Public.Back)
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Public.Forward)
        val from = ready.copy(
            page = BrowserPage("https://example.com", isBackAvailable = true, isForwardAvailable = true),
        )
        val loading = from.copy(page = from.page.copy(isLoading = true))
        BrowserMachineSpec.assertTransition(from, BrowserIntent.Public.Back, loading, listOf(BrowserEffect.Back))
        BrowserMachineSpec.assertTransition(from, BrowserIntent.Public.Forward, loading, listOf(BrowserEffect.Forward))
    }

    @Test
    fun `reload recovers a failed page and stop clears loading`() {
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Public.Reload)
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Public.Stop)
        val failed = ready.copy(page = BrowserPage("https://example.com", error = BrowserError.LoadFailed))
        val loading = failed.copy(page = failed.page.copy(error = null, isLoading = true))
        BrowserMachineSpec.assertTransition(failed, BrowserIntent.Public.Reload, loading, listOf(BrowserEffect.Reload))
        BrowserMachineSpec.assertTransition(
            loading,
            BrowserIntent.Public.Stop,
            loading.copy(page = loading.page.copy(isLoading = false)),
            listOf(BrowserEffect.Stop),
        )
    }

    @Test
    fun `safe native feedback updates title address history loading and error`() {
        val page = BrowserPage(
            "https://example.com/redirect",
            "Page",
            isBackAvailable = true,
            error = BrowserError.LoadFailed,
        )
        BrowserMachineSpec.assertTransition(ready, BrowserIntent.Internal.PageChanged(page), ready.copy(page = page))
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Internal.PageChanged(page.copy(url = "file:///secret")))
        BrowserMachineSpec.assertIgnored(ready.copy(isEnabled = false), BrowserIntent.Internal.PageChanged(page))
    }

    @Test
    fun `effect failure leaves navigation available for recovery`() {
        val from = ready.copy(page = BrowserPage("https://example.com", isLoading = true))
        BrowserMachineSpec.assertTransition(
            from,
            BrowserIntent.Internal.Failed,
            from.copy(page = from.page.copy(isLoading = false, error = BrowserError.EngineUnavailable)),
        )
    }
}
