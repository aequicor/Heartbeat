package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlin.coroutines.cancellation.CancellationException

/**
 * Transition table: Idle + Start -> Running [Observe]. Running + AvailabilityChanged -> stay,
 * updating availability and clearing transient native history when disabled. Enabled + Open(valid
 * HTTP/S) -> stay loading [Load]; invalid input -> stay with InvalidAddress. Enabled + Back/Forward
 * (history guard) -> stay loading [Back/Forward]. Enabled + Reload (nonempty URL) -> stay loading
 * [Reload]; Stop (loading guard) -> stay stopped [Stop]. Enabled + PageChanged (HTTP/S or empty) ->
 * stay with the current native page. Enabled + Display/SelectTab -> retain the profile runtime,
 * change presentation/selection only; a disabled browser accepts only Display(Hidden).
 * Enabled + Perform -> reveal panel [Perform]; results are emitted with their request identity.
 * Running + CancelOperation -> [CancelOperation]; OperationCancelled emits a correlated
 * acknowledgement, also when cancellation itself failed. Running + TabsChanged -> project tabs and
 * active tab, independent of surface attachment. Running + Completed -> emit Completed; no page
 * contents are retained in machine state. Running + ObserverFailed -> stay disabled and hidden
 * [Reobserve]; the effect already revoked the session. Running + Failed -> stay with
 * EngineUnavailable. Disabled navigation and duplicate Start are ignored.
 *
 * ```mermaid
 * stateDiagram-v2
 *     [*] --> Idle
 *     Idle --> Running: Start / Observe
 *     Running --> Running: AvailabilityChanged, PageChanged, TabsChanged, Failed
 *     Running --> Running: Open / Load, Back, Forward, Reload, Stop
 *     Running --> Running: Display, SelectTab / SelectTab
 *     Running --> Running: Perform / Perform, Completed / output Completed
 *     Running --> Running: CancelOperation / CancelOperation, OperationCancelled / output Cancelled
 *     Running --> Running: ObserverFailed / Reobserve
 * ```
 *
 * All running updates use stay so the live toggle observer survives navigation, errors and
 * disabling. Effect failures never leave a request unanswered: Perform maps to an error result
 * ("cancelled" for a cancellation raised inside the operation), CancelOperation to its
 * acknowledgement, observation to a delayed restart; a failed SelectTab is only logged.
 */
public val BrowserMachineSpec: MachineSpec<BrowserState, BrowserIntent, BrowserEffect, BrowserOutput> =
    machineSpec(BrowserMachineKey, BrowserState.Idle) {
        state<BrowserState.Idle> {
            on<BrowserIntent.Public.Start> {
                goto<BrowserState.Running> { BrowserState.Running() }
                effect { BrowserEffect.Observe }
            }
        }
        state<BrowserState.Running> {
            on<BrowserIntent.Public.Display>(
                guard = { state.isEnabled || intent.mode == BrowserDisplay.Hidden },
            ) {
                stay { state.copy(display = intent.mode) }
            }
            on<BrowserIntent.Public.SelectTab>(guard = { state.isEnabled }) {
                effect { BrowserEffect.SelectTab(intent.id) }
            }
            on<BrowserIntent.Public.Perform>(guard = { state.isEnabled }) {
                stay {
                    state.copy(
                        display =
                            if (state.display == BrowserDisplay.Hidden) {
                                BrowserDisplay.Panel
                            } else {
                                state.display
                            },
                    )
                }
                effect { BrowserEffect.Perform(intent.request) }
            }
            on<BrowserIntent.Public.Perform>(guard = { !state.isEnabled }) {
                output {
                    BrowserOutput.Completed(BrowserResult(intent.request.id, error = "Browser disabled"))
                }
            }
            on<BrowserIntent.Public.CancelOperation> {
                effect { BrowserEffect.CancelOperation(intent.id) }
            }
            on<BrowserIntent.Internal.TabsChanged> {
                stay { state.copy(tabs = intent.tabs, selectedTabId = intent.selected) }
            }
            on<BrowserIntent.Internal.OperationCancelled> {
                output { BrowserOutput.Cancelled(intent.id) }
            }
            on<BrowserIntent.Internal.Completed> {
                output { BrowserOutput.Completed(intent.result) }
            }
            on<BrowserIntent.Internal.AvailabilityChanged> {
                stay {
                    if (intent.isEnabled) state.copy(isConfigured = true, isEnabled = true) else state.disabled()
                }
            }
            on<BrowserIntent.Internal.ObserverFailed> {
                stay { state.disabled() }
                effect { BrowserEffect.Reobserve(intent.attempt) }
            }
            on<BrowserIntent.Public.Open>(
                guard = {
                    state.isEnabled && normalizeBrowserUrl(intent.address) != null
                },
            ) {
                stay {
                    state.copy(
                        page = state.page.copy(
                            url = requireNotNull(normalizeBrowserUrl(intent.address)),
                            isLoading = true,
                            error = null,
                        ),
                    )
                }
                effect { BrowserEffect.Load(requireNotNull(normalizeBrowserUrl(intent.address))) }
            }
            on<BrowserIntent.Public.Open>(
                guard = {
                    state.isEnabled && normalizeBrowserUrl(intent.address) == null
                },
            ) {
                stay { state.copy(page = state.page.copy(error = BrowserError.InvalidAddress)) }
            }
            on<BrowserIntent.Public.Back>(
                guard = { state.isEnabled && state.page.isBackAvailable },
            ) {
                stay { state.copy(page = state.page.copy(isLoading = true, error = null)) }
                effect { BrowserEffect.Back }
            }
            on<BrowserIntent.Public.Forward>(
                guard = { state.isEnabled && state.page.isForwardAvailable },
            ) {
                stay { state.copy(page = state.page.copy(isLoading = true, error = null)) }
                effect { BrowserEffect.Forward }
            }
            on<BrowserIntent.Public.Reload>(
                guard = { state.isEnabled && state.page.url.isNotEmpty() },
            ) {
                stay { state.copy(page = state.page.copy(isLoading = true, error = null)) }
                effect { BrowserEffect.Reload }
            }
            on<BrowserIntent.Public.Stop>(guard = { state.isEnabled && state.page.isLoading }) {
                stay { state.copy(page = state.page.copy(isLoading = false)) }
                effect { BrowserEffect.Stop }
            }
            on<BrowserIntent.Internal.PageChanged>(
                guard = {
                    state.isEnabled && (intent.page.url.isEmpty() || isBrowserUrlAllowed(intent.page.url))
                },
            ) {
                stay { state.copy(page = intent.page) }
            }
            on<BrowserIntent.Internal.Failed> {
                stay {
                    state.copy(
                        isConfigured = true,
                        page = state.page.copy(
                            isLoading = false,
                            error = BrowserError.EngineUnavailable,
                        ),
                    )
                }
            }
        }
        // Handlers answer results and recovered failures themselves; only escaped failures and
        // cancellations raised inside an active effect arrive here, so each request is answered once.
        onEffectFailure { effect, error ->
            when (effect) {
                is BrowserEffect.Perform ->
                    BrowserIntent.Internal.Completed(
                        BrowserResult(
                            effect.request.id,
                            error = if (error is CancellationException) {
                                "Browser operation cancelled"
                            } else {
                                "Browser operation failed"
                            },
                        ),
                    )

                is BrowserEffect.CancelOperation -> BrowserIntent.Internal.OperationCancelled(effect.id)

                // Selection is presentation-only; a stale tab must not mark the whole engine unavailable.
                is BrowserEffect.SelectTab -> null

                BrowserEffect.Observe -> BrowserIntent.Internal.ObserverFailed(attempt = 1)

                is BrowserEffect.Reobserve -> BrowserIntent.Internal.ObserverFailed(effect.attempt + 1)

                is BrowserEffect.Load,
                BrowserEffect.Back,
                BrowserEffect.Forward,
                BrowserEffect.Reload,
                BrowserEffect.Stop,
                -> BrowserIntent.Internal.Failed
            }
        }
    }

/** Revoked availability hides the surface and drops transient native state, keeping the address. */
private fun BrowserState.Running.disabled(): BrowserState.Running = copy(
    isConfigured = true,
    isEnabled = false,
    display = BrowserDisplay.Hidden,
    page = page.copy(
        isLoading = false,
        isBackAvailable = false,
        isForwardAvailable = false,
        error = null,
    ),
)
