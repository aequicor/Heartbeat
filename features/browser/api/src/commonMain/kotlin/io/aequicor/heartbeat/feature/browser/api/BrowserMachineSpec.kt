package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Transition table:
 * Idle + Start -> Running [Observe].
 * Running + AvailabilityChanged -> stay, updating availability and clearing transient native history when disabled.
 * Enabled + Open(valid HTTP/S) -> stay loading [Load]; invalid input -> stay with InvalidAddress.
 * Enabled + Back/Forward (history guard) -> stay loading [Back/Forward].
 * Enabled + Reload (nonempty URL) -> stay loading [Reload]; Stop (loading guard) -> stay stopped [Stop].
 * Enabled + PageChanged (HTTP/S or empty) -> stay with the current native page.
 * Running + Failed -> stay with EngineUnavailable. Disabled navigation and duplicate Start are ignored.
 *
 * ```mermaid
 * stateDiagram-v2
 *     [*] --> Idle
 *     Idle --> Running: Start / Observe
 *     Running --> Running: AvailabilityChanged, Open, Back, Forward, Reload, Stop, PageChanged, Failed
 * ```
 *
 * All running updates use stay so the live toggle observer survives navigation, errors and disabling.
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
            on<BrowserIntent.Internal.AvailabilityChanged> {
                stay {
                    state.copy(
                        isConfigured = true,
                        isEnabled = intent.isEnabled,
                        page = if (intent.isEnabled) {
                            state.page
                        } else {
                            state.page.copy(
                                isLoading = false,
                                isBackAvailable = false,
                                isForwardAvailable = false,
                                error = null,
                            )
                        },
                    )
                }
            }
            on<BrowserIntent.Public.Open>(guard = {
                state.isEnabled && normalizeBrowserUrl(intent.address) != null
            }) {
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
            on<BrowserIntent.Public.Open>(guard = {
                state.isEnabled && normalizeBrowserUrl(intent.address) == null
            }) {
                stay { state.copy(page = state.page.copy(error = BrowserError.InvalidAddress)) }
            }
            on<BrowserIntent.Public.Back>(guard = { state.isEnabled && state.page.isBackAvailable }) {
                stay { state.copy(page = state.page.copy(isLoading = true, error = null)) }
                effect { BrowserEffect.Back }
            }
            on<BrowserIntent.Public.Forward>(guard = { state.isEnabled && state.page.isForwardAvailable }) {
                stay { state.copy(page = state.page.copy(isLoading = true, error = null)) }
                effect { BrowserEffect.Forward }
            }
            on<BrowserIntent.Public.Reload>(guard = { state.isEnabled && state.page.url.isNotEmpty() }) {
                stay { state.copy(page = state.page.copy(isLoading = true, error = null)) }
                effect { BrowserEffect.Reload }
            }
            on<BrowserIntent.Public.Stop>(guard = { state.isEnabled && state.page.isLoading }) {
                stay { state.copy(page = state.page.copy(isLoading = false)) }
                effect { BrowserEffect.Stop }
            }
            on<BrowserIntent.Internal.PageChanged>(guard = {
                state.isEnabled && (intent.page.url.isEmpty() || isBrowserUrlAllowed(intent.page.url))
            }) {
                stay { state.copy(page = intent.page) }
            }
            on<BrowserIntent.Internal.Failed> {
                stay {
                    state.copy(
                        isConfigured = true,
                        page = state.page.copy(isLoading = false, error = BrowserError.EngineUnavailable),
                    )
                }
            }
        }
        onEffectFailure { _, _ -> BrowserIntent.Internal.Failed }
    }
