package io.aequicor.heartbeat.feature.chromiumbridge.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * The profile's bridge to Chromium browsers. While [ChromiumBridgeState.Enabled] lasts, the long-running
 * [ChromiumBridgeEffect.Serve] keeps the app socket open; updates stay in the state, so the socket survives them,
 * and leaving it for [ChromiumBridgeState.Disabled] cancels the effect, which closes and removes the socket.
 * Registration changes run one at a time.
 *
 * | From | Intent | Guard | To | Effect / Output |
 * |---|---|---|---|---|
 * | Idle / Disabled | AvailabilityChanged | enabled and supported | Enabled | Serve, ScanHosts, LocateExtension |
 * | Idle / Disabled | AvailabilityChanged | otherwise | Disabled(isSupported) | |
 * | Enabled | AvailabilityChanged | enabled and supported | stay(isAgentControlEnabled) | |
 * | Enabled | AvailabilityChanged | otherwise | Disabled(isSupported) | |
 * | Enabled | ServerChanged | | stay(server) | |
 * | Enabled | HostsScanned | | stay(hosts) | |
 * | Enabled | Rescan | | stay | ScanHosts |
 * | Enabled | Register / Unregister | no change in progress | stay(registering) | Register / Unregister |
 * | Enabled | RegistrationFinished | | stay(host updated, registering cleared) | |
 * | Enabled | RegistrationFailed | | stay(registering cleared) | RegistrationFailed |
 * | Enabled | ConnectionsChanged | | stay(connections) | |
 * | Enabled | TabsChanged | | stay(controlledTabs) | |
 * | Enabled | RevokeTabs | | stay | RevokeTabs |
 * | Enabled | ExtensionLocated | | stay(extensionFolder) | |
 * | Enabled | RevealExtension | not preparing | stay(Downloading) | PrepareExtension |
 * | Enabled | ExtensionProgress | preparing | stay(preparation) | |
 * | Enabled | ExtensionPrepared | preparing | stay(folder, Idle) | RevealExtension |
 * | Enabled | ExtensionFailed | preparing | stay(Failed) | |
 * | any | EffectFailed | | stay | |
 *
 * A failed registration effect becomes RegistrationFailed(WriteFailed), a failed Serve becomes
 * ServerChanged(Failed); other failures are EffectFailed.
 */
public val ChromiumBridgeMachineSpec: MachineSpec<
    ChromiumBridgeState,
    ChromiumBridgeIntent,
    ChromiumBridgeEffect,
    ChromiumBridgeOutput,
> = machineSpec(ChromiumBridgeMachineKey, ChromiumBridgeState.Idle) {
    state<ChromiumBridgeState.Idle> {
        on<ChromiumBridgeIntent.Internal.AvailabilityChanged>(guard = { intent.isActive }) {
            goto<ChromiumBridgeState.Enabled> { intent.enabledState() }
            effect { ChromiumBridgeEffect.Serve }
            effect { ChromiumBridgeEffect.ScanHosts }
            effect { ChromiumBridgeEffect.LocateExtension }
        }
        on<ChromiumBridgeIntent.Internal.AvailabilityChanged>(guard = { !intent.isActive }) {
            goto<ChromiumBridgeState.Disabled> { ChromiumBridgeState.Disabled(intent.isSupported) }
        }
    }
    state<ChromiumBridgeState.Disabled> {
        on<ChromiumBridgeIntent.Internal.AvailabilityChanged>(guard = { intent.isActive }) {
            goto<ChromiumBridgeState.Enabled> { intent.enabledState() }
            effect { ChromiumBridgeEffect.Serve }
            effect { ChromiumBridgeEffect.ScanHosts }
            effect { ChromiumBridgeEffect.LocateExtension }
        }
        on<ChromiumBridgeIntent.Internal.AvailabilityChanged>(guard = { !intent.isActive }) {
            stay { ChromiumBridgeState.Disabled(intent.isSupported) }
        }
    }
    state<ChromiumBridgeState.Enabled> {
        on<ChromiumBridgeIntent.Internal.AvailabilityChanged>(guard = { intent.isActive }) {
            stay { state.copy(isAgentControlEnabled = intent.isAgentControlEnabled) }
        }
        on<ChromiumBridgeIntent.Internal.AvailabilityChanged>(guard = { !intent.isActive }) {
            goto<ChromiumBridgeState.Disabled> { ChromiumBridgeState.Disabled(intent.isSupported) }
        }
        on<ChromiumBridgeIntent.Internal.ServerChanged> { stay { state.copy(server = intent.status) } }
        on<ChromiumBridgeIntent.Internal.HostsScanned> { stay { state.copy(hosts = intent.hosts) } }
        on<ChromiumBridgeIntent.Public.Rescan> { effect { ChromiumBridgeEffect.ScanHosts } }
        on<ChromiumBridgeIntent.Public.Register>(guard = { state.registering == null }) {
            stay { state.copy(registering = intent.browser) }
            effect { ChromiumBridgeEffect.Register(intent.browser) }
        }
        on<ChromiumBridgeIntent.Public.Unregister>(guard = { state.registering == null }) {
            stay { state.copy(registering = intent.browser) }
            effect { ChromiumBridgeEffect.Unregister(intent.browser) }
        }
        on<ChromiumBridgeIntent.Internal.RegistrationFinished> {
            stay { state.copy(hosts = state.hosts.replacing(intent.host), registering = null) }
        }
        on<ChromiumBridgeIntent.Internal.RegistrationFailed> {
            stay { state.copy(registering = null) }
            output { ChromiumBridgeOutput.RegistrationFailed(intent.browser, intent.reason) }
        }
        on<ChromiumBridgeIntent.Internal.ConnectionsChanged> { stay { state.copy(connections = intent.connections) } }
        on<ChromiumBridgeIntent.Internal.TabsChanged> { stay { state.copy(controlledTabs = intent.controlledTabs) } }
        on<ChromiumBridgeIntent.Public.RevokeTabs> { effect { ChromiumBridgeEffect.RevokeTabs } }
        on<ChromiumBridgeIntent.Internal.ExtensionLocated> {
            stay { state.copy(extensionFolder = state.extensionFolder ?: intent.folder) }
        }
        on<ChromiumBridgeIntent.Public.RevealExtension>(guard = { !state.extensionPreparation.isPreparing }) {
            stay { state.copy(extensionPreparation = ExtensionPreparation.Downloading()) }
            effect { ChromiumBridgeEffect.PrepareExtension }
        }
        on<ChromiumBridgeIntent.Internal.ExtensionProgress>(guard = { state.extensionPreparation.isPreparing }) {
            stay { state.copy(extensionPreparation = intent.preparation) }
        }
        on<ChromiumBridgeIntent.Internal.ExtensionPrepared>(guard = { state.extensionPreparation.isPreparing }) {
            stay { state.copy(extensionFolder = intent.folder, extensionPreparation = ExtensionPreparation.Idle) }
            effect { ChromiumBridgeEffect.RevealExtension(intent.folder) }
        }
        on<ChromiumBridgeIntent.Internal.ExtensionFailed>(guard = { state.extensionPreparation.isPreparing }) {
            stay { state.copy(extensionPreparation = ExtensionPreparation.Failed) }
        }
    }
    any { on<ChromiumBridgeIntent.Internal.EffectFailed>() }
    onEffectFailure { effect, _ ->
        when (effect) {
            is ChromiumBridgeEffect.Register ->
                ChromiumBridgeIntent.Internal.RegistrationFailed(effect.browser, HostRegistrationFailure.WriteFailed)

            is ChromiumBridgeEffect.Unregister ->
                ChromiumBridgeIntent.Internal.RegistrationFailed(effect.browser, HostRegistrationFailure.WriteFailed)

            ChromiumBridgeEffect.Serve -> ChromiumBridgeIntent.Internal.ServerChanged(BridgeServerStatus.Failed)

            ChromiumBridgeEffect.PrepareExtension -> ChromiumBridgeIntent.Internal.ExtensionFailed

            ChromiumBridgeEffect.ScanHosts,
            ChromiumBridgeEffect.RevokeTabs,
            ChromiumBridgeEffect.LocateExtension,
            is ChromiumBridgeEffect.RevealExtension,
            -> ChromiumBridgeIntent.Internal.EffectFailed
        }
    }
}

private val ChromiumBridgeIntent.Internal.AvailabilityChanged.isActive: Boolean get() = isEnabled && isSupported

private fun ChromiumBridgeIntent.Internal.AvailabilityChanged.enabledState() =
    ChromiumBridgeState.Enabled(isAgentControlEnabled = isAgentControlEnabled)

private fun List<BrowserHost>.replacing(host: BrowserHost): List<BrowserHost> =
    if (any { it.browser == host.browser }) map { if (it.browser == host.browser) host else it } else this + host

private val ExtensionPreparation.isPreparing: Boolean
    get() = this is ExtensionPreparation.Downloading || this == ExtensionPreparation.Extracting
