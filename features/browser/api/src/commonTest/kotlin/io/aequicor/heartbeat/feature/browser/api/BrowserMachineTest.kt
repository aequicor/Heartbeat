package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BrowserMachineTest {
    private val ready = BrowserState.Running(isConfigured = true, isEnabled = true)

    @Test
    fun `tools reveal the panel and cancellation remains available when disabled`() {
        val request = BrowserRequest("operation", BrowserOperation.NewTab)
        BrowserMachineSpec.assertTransition(
            ready,
            BrowserIntent.Public.Perform(request),
            ready.copy(display = BrowserDisplay.Panel),
            listOf(BrowserEffect.Perform(request)),
        )
        val disabled = ready.copy(isEnabled = false)
        BrowserMachineSpec.assertTransition(
            disabled,
            BrowserIntent.Public.Perform(request),
            disabled,
            outputs = listOf(BrowserOutput.Completed(BrowserResult(request.id, error = "Browser disabled"))),
        )
        BrowserMachineSpec.assertTransition(
            disabled,
            BrowserIntent.Public.CancelOperation(request.id),
            disabled,
            listOf(BrowserEffect.CancelOperation(request.id)),
        )
    }

    @Test
    fun `display changes preserve tabs and never reload pages`() {
        val state =
            ready.copy(
                tabs = listOf(BrowserTab("tab")),
                selectedTabId = "tab",
                display = BrowserDisplay.Panel,
            )
        listOf(BrowserDisplay.Expanded, BrowserDisplay.Hidden, BrowserDisplay.Panel).forEach { mode ->
            BrowserMachineSpec.assertTransition(
                state,
                BrowserIntent.Public.Display(mode),
                state.copy(display = mode),
            )
        }
        BrowserMachineSpec.assertTransition(
            state,
            BrowserIntent.Public.SelectTab("tab"),
            state,
            listOf(BrowserEffect.SelectTab("tab")),
        )
    }

    @Test
    fun `both additional capabilities start disabled`() {
        assertFalse(BrowserAgentControl.default)
        assertFalse(BrowserResearchAccess.default)
    }

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
    fun `reload retries unavailable runtime even before a first URL but not an untouched empty page`() {
        val failed = ready.copy(page = BrowserPage(error = BrowserError.EngineUnavailable))
        BrowserMachineSpec.assertTransition(
            failed,
            BrowserIntent.Public.Reload,
            ready.copy(page = BrowserPage(isLoading = true)),
            listOf(BrowserEffect.Reload),
        )
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Public.Reload)
        BrowserMachineSpec.assertIgnored(failed.copy(isEnabled = false), BrowserIntent.Public.Reload)
    }

    @Test
    fun `disabling clears loading and surface history but retains the address`() {
        val from = ready.copy(
            page = BrowserPage("https://example.com", isLoading = true, isBackAvailable = true),
        )
        val disabled = from.copy(
            isEnabled = false,
            page = from.page.copy(isLoading = false, isBackAvailable = false),
        )
        BrowserMachineSpec.assertTransition(
            from,
            BrowserIntent.Internal.AvailabilityChanged(false),
            disabled,
        )
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
            page = BrowserPage(
                "https://example.com",
                isBackAvailable = true,
                isForwardAvailable = true,
            ),
        )
        val loading = from.copy(page = from.page.copy(isLoading = true))
        BrowserMachineSpec.assertTransition(
            from,
            BrowserIntent.Public.Back,
            loading,
            listOf(BrowserEffect.Back),
        )
        BrowserMachineSpec.assertTransition(
            from,
            BrowserIntent.Public.Forward,
            loading,
            listOf(BrowserEffect.Forward),
        )
    }

    @Test
    fun `reload recovers a failed page and stop clears loading`() {
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Public.Reload)
        BrowserMachineSpec.assertIgnored(ready, BrowserIntent.Public.Stop)
        val failed = ready.copy(page = BrowserPage("https://example.com", error = BrowserError.LoadFailed))
        val loading = failed.copy(page = failed.page.copy(error = null, isLoading = true))
        BrowserMachineSpec.assertTransition(
            failed,
            BrowserIntent.Public.Reload,
            loading,
            listOf(BrowserEffect.Reload),
        )
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
        BrowserMachineSpec.assertTransition(
            ready,
            BrowserIntent.Internal.PageChanged(page),
            ready.copy(page = page),
        )
        BrowserMachineSpec.assertIgnored(
            ready,
            BrowserIntent.Internal.PageChanged(page.copy(url = "file:///secret")),
        )
        BrowserMachineSpec.assertIgnored(
            ready.copy(isEnabled = false),
            BrowserIntent.Internal.PageChanged(page),
        )
    }

    @Test
    fun `effect failure leaves navigation available for recovery`() {
        val from = ready.copy(page = BrowserPage("https://example.com", isLoading = true))
        BrowserMachineSpec.assertTransition(
            from,
            BrowserIntent.Internal.Failed,
            from.copy(
                page = from.page.copy(isLoading = false, error = BrowserError.EngineUnavailable),
            ),
        )
    }

    @Test
    fun `tab inventory updates tabs and selection without effects`() {
        val tabs = listOf(BrowserTab("a"), BrowserTab("b"))
        BrowserMachineSpec.assertTransition(
            ready,
            BrowserIntent.Internal.TabsChanged(tabs, "b"),
            ready.copy(tabs = tabs, selectedTabId = "b"),
        )
        val disabled = ready.copy(isEnabled = false, tabs = tabs, selectedTabId = "b")
        BrowserMachineSpec.assertTransition(
            disabled,
            BrowserIntent.Internal.TabsChanged(emptyList(), null),
            disabled.copy(tabs = emptyList(), selectedTabId = null),
        )
    }

    @Test
    fun `operation results and cancellation acknowledgements become outputs`() {
        val result = BrowserResult("operation", error = "boom")
        BrowserMachineSpec.assertTransition(
            ready,
            BrowserIntent.Internal.Completed(result),
            ready,
            outputs = listOf(BrowserOutput.Completed(result)),
        )
        BrowserMachineSpec.assertTransition(
            ready,
            BrowserIntent.Internal.OperationCancelled("operation"),
            ready,
            outputs = listOf(BrowserOutput.Cancelled("operation")),
        )
    }

    @Test
    fun `disabling hides a visible browser`() {
        val expanded = ready.copy(display = BrowserDisplay.Expanded)
        BrowserMachineSpec.assertTransition(
            expanded,
            BrowserIntent.Internal.AvailabilityChanged(false),
            expanded.copy(isEnabled = false, display = BrowserDisplay.Hidden),
        )
    }

    @Test
    fun `disabled browser can only be hidden and does not select tabs`() {
        val disabled = ready.copy(isEnabled = false, tabs = listOf(BrowserTab("tab")))
        BrowserMachineSpec.assertIgnored(disabled, BrowserIntent.Public.Display(BrowserDisplay.Panel))
        BrowserMachineSpec.assertIgnored(disabled, BrowserIntent.Public.Display(BrowserDisplay.Expanded))
        BrowserMachineSpec.assertIgnored(disabled, BrowserIntent.Public.SelectTab("tab"))
        BrowserMachineSpec.assertTransition(
            disabled,
            BrowserIntent.Public.Display(BrowserDisplay.Hidden),
            disabled,
        )
    }

    @Test
    fun `failed request effects still answer their requests`() {
        val request = BrowserRequest("operation", BrowserOperation.NewTab)
        val error = IllegalStateException("boom")
        assertEquals(
            BrowserIntent.Internal.Completed(BrowserResult(request.id, error = "Browser operation failed")),
            BrowserMachineSpec.onEffectFailure(BrowserEffect.Perform(request), error),
        )
        assertEquals(
            BrowserIntent.Internal.Completed(BrowserResult(request.id, error = "Browser operation cancelled")),
            BrowserMachineSpec.onEffectFailure(BrowserEffect.Perform(request), CancellationException("timeout")),
        )
        assertEquals(
            BrowserIntent.Internal.OperationCancelled(request.id),
            BrowserMachineSpec.onEffectFailure(BrowserEffect.CancelOperation(request.id), error),
        )
        assertNull(BrowserMachineSpec.onEffectFailure(BrowserEffect.SelectTab("tab"), error))
        assertEquals(
            BrowserIntent.Internal.Failed,
            BrowserMachineSpec.onEffectFailure(BrowserEffect.Load("https://example.com"), error),
        )
    }

    @Test
    fun `observer failure disables and hides the browser and restarts observation with growing attempts`() {
        val error = IllegalStateException("boom")
        assertEquals(
            BrowserIntent.Internal.ObserverFailed(1),
            BrowserMachineSpec.onEffectFailure(BrowserEffect.Observe, error),
        )
        assertEquals(
            BrowserIntent.Internal.ObserverFailed(3),
            BrowserMachineSpec.onEffectFailure(BrowserEffect.Reobserve(2), error),
        )
        val from = ready.copy(
            display = BrowserDisplay.Panel,
            page = BrowserPage("https://example.com", isLoading = true, isForwardAvailable = true),
        )
        val disabled = from.copy(
            isEnabled = false,
            display = BrowserDisplay.Hidden,
            page = from.page.copy(isLoading = false, isForwardAvailable = false),
        )
        BrowserMachineSpec.assertTransition(
            from,
            BrowserIntent.Internal.ObserverFailed(2),
            disabled,
            listOf(BrowserEffect.Reobserve(2)),
        )
        BrowserMachineSpec.assertIgnored(disabled, BrowserIntent.Public.Open("example.com"))
        BrowserMachineSpec.assertTransition(
            disabled,
            BrowserIntent.Internal.AvailabilityChanged(true),
            disabled.copy(isEnabled = true),
        )
    }
}
