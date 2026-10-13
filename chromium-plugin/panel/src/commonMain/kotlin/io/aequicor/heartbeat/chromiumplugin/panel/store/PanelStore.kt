package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelInbound
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelOutbound
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce

private val log = Log.tag("Panel")

/** The panel shell over [link]: reflects the link status and agent control, and sends the user's consent. */
internal fun panelStore(factory: HeartbeatStoreFactory, link: PanelLink): Store<PanelState, PanelIntent, PanelAction> =
    factory.create("ChromiumPanel", PanelState(), onError = { this }) {
        init {
            launch { link.messages.collect { message -> updateState { reflect(message) } } }
        }
        reduce { intent ->
            when (intent) {
                is PanelIntent.SetAgentControl -> {
                    log.i { "agent control allowed=${intent.isAllowed}" }
                    link.send(PanelOutbound.Consent(intent.isAllowed))
                }

                PanelIntent.RevokeTabs -> {
                    log.i { "revoke agent tabs" }
                    link.send(PanelOutbound.RevokeTabs)
                }
            }
        }
    }

private fun PanelState.reflect(message: PanelInbound): PanelState = when (message) {
    is PanelInbound.Status -> copy(status = LinkStatus.fromWire(message.status), features = message.features)
    is PanelInbound.Agent -> copy(agent = AgentControl(message.isAllowed, message.controlled))
    is PanelInbound.Event, is PanelInbound.Reply, is PanelInbound.WatchFailed -> this
}
