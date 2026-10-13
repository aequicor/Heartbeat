package io.aequicor.heartbeat.feature.chromiumbridge.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEffect
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeIntent
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Effects of the bridge machine. Collaborators are lazy, so nothing desktop-specific is created while the bridge
 * stays disabled.
 */
internal class ChromiumBridgeEffects(
    private val server: Lazy<BridgeServer>,
    private val hosts: Lazy<NativeHostRegistry>,
    private val extension: Lazy<ExtensionFolder>,
) : EffectHandler<ChromiumBridgeEffect, ChromiumBridgeIntent> {
    private val log = Log.tag("ChromiumBridgeEffects")

    override suspend fun handle(effect: ChromiumBridgeEffect, machine: EffectScope<ChromiumBridgeIntent>) {
        when (effect) {
            ChromiumBridgeEffect.Serve -> serve(machine)

            ChromiumBridgeEffect.ScanHosts -> scan(machine)

            is ChromiumBridgeEffect.Register -> change(effect.browser, machine) { hosts.value.register(it) }

            is ChromiumBridgeEffect.Unregister -> change(effect.browser, machine) { hosts.value.unregister(it) }

            ChromiumBridgeEffect.LocateExtension ->
                machine.send(ChromiumBridgeIntent.Internal.ExtensionLocated(extension.value.locate()))

            is ChromiumBridgeEffect.RevealExtension -> extension.value.reveal(effect.folder)

            ChromiumBridgeEffect.RevokeTabs -> {
                val answered = server.value.revokeTabs()
                log.i { "Agent tabs revoked in $answered browsers" }
            }
        }
    }

    private suspend fun serve(machine: EffectScope<ChromiumBridgeIntent>) = coroutineScope {
        val clients = launch {
            server.value.clients().distinctUntilChanged().collect {
                machine.send(ChromiumBridgeIntent.Internal.ConnectionsChanged(it))
            }
        }
        val tabs = launch {
            server.value.controlledTabs().distinctUntilChanged().collect {
                machine.send(ChromiumBridgeIntent.Internal.TabsChanged(it))
            }
        }
        server.value.serve { machine.send(ChromiumBridgeIntent.Internal.ServerChanged(it)) }
        clients.cancel()
        tabs.cancel()
    }

    private suspend fun scan(machine: EffectScope<ChromiumBridgeIntent>) {
        machine.send(ChromiumBridgeIntent.Internal.HostsScanned(hosts.value.scan()))
    }

    private suspend fun change(
        browser: ChromiumBrowser,
        machine: EffectScope<ChromiumBridgeIntent>,
        action: suspend (ChromiumBrowser) -> BrowserHost,
    ) {
        val result = try {
            ChromiumBridgeIntent.Internal.RegistrationFinished(action(browser))
        } catch (e: HostRegistrationException) {
            log.w(e) { "Host registration change refused browser=$browser problem=${e.problem}" }
            ChromiumBridgeIntent.Internal.RegistrationFailed(browser, e.problem)
        }
        machine.send(result)
    }
}
