package io.aequicor.heartbeat.feature.chromiumbridge.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals

class ChromiumBridgeMachineSpecTest {
    private val spec = ChromiumBridgeMachineSpec
    private val chrome = BrowserHost(ChromiumBrowser.Chrome, isDetected = true, HostRegistration.NotRegistered)
    private val edge = BrowserHost(ChromiumBrowser.Edge, isDetected = false, HostRegistration.NotRegistered)
    private val enabled = ChromiumBridgeState.Enabled(hosts = listOf(chrome, edge))

    @Test
    fun `enabling on a supported platform serves the socket and scans browsers`() {
        listOf(ChromiumBridgeState.Idle, ChromiumBridgeState.Disabled(isSupported = true)).forEach { from ->
            spec.assertTransition(
                from = from,
                intent = availability(isEnabled = true, isAgentControl = true),
                to = ChromiumBridgeState.Enabled(isAgentControlEnabled = true),
                effects = listOf(
                    ChromiumBridgeEffect.Serve,
                    ChromiumBridgeEffect.ScanHosts,
                    ChromiumBridgeEffect.LocateExtension,
                ),
            )
        }
    }

    @Test
    fun `an off toggle or an unsupported platform keeps the bridge disabled`() {
        spec.assertTransition(
            from = ChromiumBridgeState.Idle,
            intent = availability(isEnabled = false),
            to = ChromiumBridgeState.Disabled(isSupported = true),
        )
        spec.assertTransition(
            from = ChromiumBridgeState.Idle,
            intent = availability(isEnabled = true, isSupported = false),
            to = ChromiumBridgeState.Disabled(isSupported = false),
        )
        spec.assertTransition(
            from = ChromiumBridgeState.Disabled(isSupported = true),
            intent = availability(isEnabled = true, isSupported = false),
            to = ChromiumBridgeState.Disabled(isSupported = false),
        )
    }

    @Test
    fun `toggle updates keep serving and disabling leaves the state`() {
        spec.assertTransition(
            from = enabled,
            intent = availability(isEnabled = true, isAgentControl = true),
            to = enabled.copy(isAgentControlEnabled = true),
        )
        spec.assertTransition(
            from = enabled,
            intent = availability(isEnabled = false),
            to = ChromiumBridgeState.Disabled(isSupported = true),
        )
    }

    @Test
    fun `server hosts connections and tabs update the enabled state`() {
        val browser = ConnectedBrowser(1, "Google Chrome", "140", "0.1.0", isAgentControlAllowed = false)
        spec.assertTransition(
            enabled,
            ChromiumBridgeIntent.Internal.ServerChanged(BridgeServerStatus.Listening),
            enabled.copy(server = BridgeServerStatus.Listening),
        )
        spec.assertTransition(
            enabled,
            ChromiumBridgeIntent.Internal.HostsScanned(listOf(edge)),
            enabled.copy(hosts = listOf(edge)),
        )
        spec.assertTransition(
            enabled,
            ChromiumBridgeIntent.Internal.ConnectionsChanged(listOf(browser)),
            enabled.copy(connections = listOf(browser)),
        )
        spec.assertTransition(enabled, ChromiumBridgeIntent.Internal.TabsChanged(3), enabled.copy(controlledTabs = 3))
    }

    @Test
    fun `rescan and revoke run their effects`() {
        spec.assertTransition(
            enabled,
            ChromiumBridgeIntent.Public.Rescan,
            enabled,
            effects = listOf(ChromiumBridgeEffect.ScanHosts),
        )
        spec.assertTransition(
            enabled,
            ChromiumBridgeIntent.Public.RevokeTabs,
            enabled,
            effects = listOf(ChromiumBridgeEffect.RevokeTabs),
        )
    }

    @Test
    fun `the extension folder is remembered and shown only once known`() {
        spec.assertIgnored(enabled, ChromiumBridgeIntent.Public.RevealExtension)
        val located = enabled.copy(extensionFolder = "/app/chromium-plugin")
        spec.assertTransition(enabled, ChromiumBridgeIntent.Internal.ExtensionLocated("/app/chromium-plugin"), located)
        spec.assertTransition(
            located,
            ChromiumBridgeIntent.Public.RevealExtension,
            located,
            effects = listOf(ChromiumBridgeEffect.RevealExtension("/app/chromium-plugin")),
        )
    }

    @Test
    fun `registration changes run one at a time`() {
        val registering = enabled.copy(registering = ChromiumBrowser.Chrome)
        spec.assertTransition(
            enabled,
            ChromiumBridgeIntent.Public.Register(ChromiumBrowser.Chrome),
            registering,
            effects = listOf(ChromiumBridgeEffect.Register(ChromiumBrowser.Chrome)),
        )
        spec.assertTransition(
            enabled,
            ChromiumBridgeIntent.Public.Unregister(ChromiumBrowser.Edge),
            enabled.copy(registering = ChromiumBrowser.Edge),
            effects = listOf(ChromiumBridgeEffect.Unregister(ChromiumBrowser.Edge)),
        )
        spec.assertIgnored(registering, ChromiumBridgeIntent.Public.Register(ChromiumBrowser.Edge))
        spec.assertIgnored(registering, ChromiumBridgeIntent.Public.Unregister(ChromiumBrowser.Chrome))
    }

    @Test
    fun `a finished registration replaces its browser and a failure is reported`() {
        val registering = enabled.copy(registering = ChromiumBrowser.Chrome)
        val current = chrome.copy(registration = HostRegistration.Current)
        spec.assertTransition(
            registering,
            ChromiumBridgeIntent.Internal.RegistrationFinished(current),
            enabled.copy(hosts = listOf(current, edge)),
        )
        spec.assertTransition(
            registering,
            ChromiumBridgeIntent.Internal.RegistrationFailed(
                ChromiumBrowser.Chrome,
                HostRegistrationFailure.Translocated,
            ),
            enabled,
            outputs = listOf(
                ChromiumBridgeOutput.RegistrationFailed(ChromiumBrowser.Chrome, HostRegistrationFailure.Translocated),
            ),
        )
    }

    @Test
    fun `settings commands are ignored while the bridge is off`() {
        val disabled = ChromiumBridgeState.Disabled(isSupported = true)
        spec.assertIgnored(disabled, ChromiumBridgeIntent.Public.Register(ChromiumBrowser.Chrome))
        spec.assertIgnored(disabled, ChromiumBridgeIntent.Public.Rescan)
        spec.assertIgnored(disabled, ChromiumBridgeIntent.Public.RevokeTabs)
        spec.assertIgnored(ChromiumBridgeState.Idle, ChromiumBridgeIntent.Internal.TabsChanged(1))
    }

    @Test
    fun `effect failures map to their results`() {
        val error = IllegalStateException("boom")
        assertEquals(
            ChromiumBridgeIntent.Internal.RegistrationFailed(
                ChromiumBrowser.Brave,
                HostRegistrationFailure.WriteFailed,
            ),
            spec.onEffectFailure(ChromiumBridgeEffect.Register(ChromiumBrowser.Brave), error),
        )
        assertEquals(
            ChromiumBridgeIntent.Internal.ServerChanged(BridgeServerStatus.Failed),
            spec.onEffectFailure(ChromiumBridgeEffect.Serve, error),
        )
        assertEquals(
            ChromiumBridgeIntent.Internal.EffectFailed,
            spec.onEffectFailure(ChromiumBridgeEffect.ScanHosts, error),
        )
        spec.assertTransition(enabled, ChromiumBridgeIntent.Internal.EffectFailed, enabled)
    }

    private fun availability(isEnabled: Boolean, isSupported: Boolean = true, isAgentControl: Boolean = false) =
        ChromiumBridgeIntent.Internal.AvailabilityChanged(isEnabled, isSupported, isAgentControl)
}
