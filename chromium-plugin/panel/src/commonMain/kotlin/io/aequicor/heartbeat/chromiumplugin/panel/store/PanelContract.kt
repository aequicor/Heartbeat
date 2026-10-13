package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelFeatures
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** Link status of the extension (`native-link.js`); [wire] is its name on the panel port. */
internal enum class LinkStatus(val wire: String) {
    Connecting("connecting"),
    Handshaking("handshaking"),
    HostMissing("host_missing"),
    Forbidden("forbidden"),
    WaitingForApp("waiting_for_app"),
    Ready("ready"),
    Incompatible("incompatible"),
    Disconnected("disconnected"),
    ;

    companion object {
        /** An unknown status of a newer extension counts as a lost connection. */
        fun fromWire(wire: String): LinkStatus = entries.firstOrNull { it.wire == wire } ?: Disconnected
    }
}

/** Consent to agent control in this browser and the number of tabs agents drive. */
internal data class AgentControl(val isAllowed: Boolean = false, val controlled: Int = 0)

/** The panel shell: the link to Heartbeat and agent control in this browser. */
internal data class PanelState(
    val status: LinkStatus = LinkStatus.Connecting,
    val features: PanelFeatures? = null,
    val agent: AgentControl = AgentControl(),
) : MVIState

/** What the user does in the panel shell. */
internal sealed interface PanelIntent : MVIIntent {
    /** Allows or forbids agent control in this browser. */
    data class SetAgentControl(val isAllowed: Boolean) : PanelIntent

    /** Takes every tab back from agents. */
    data object RevokeTabs : PanelIntent
}

/** The shell has no one-off effects. */
internal sealed interface PanelAction : MVIAction

/** What the panel says instead of (or above) the studio. */
internal enum class PanelNotice(val isProblem: Boolean) {
    Connecting(isProblem = false),
    HostMissing(isProblem = true),
    Forbidden(isProblem = true),
    WaitingForApp(isProblem = true),
    Incompatible(isProblem = true),
    Disconnected(isProblem = true),
    StudioOff(isProblem = true),
}

/** The notice for the link status, or `null` when the studio is usable. */
internal val PanelState.notice: PanelNotice?
    get() = when (status) {
        LinkStatus.Connecting, LinkStatus.Handshaking -> PanelNotice.Connecting
        LinkStatus.HostMissing -> PanelNotice.HostMissing
        LinkStatus.Forbidden -> PanelNotice.Forbidden
        LinkStatus.WaitingForApp -> PanelNotice.WaitingForApp
        LinkStatus.Incompatible -> PanelNotice.Incompatible
        LinkStatus.Disconnected -> PanelNotice.Disconnected
        LinkStatus.Ready -> PanelNotice.StudioOff.takeIf { features?.isStudio != true }
    }

/** The app lets agents drive this browser, so the consent is offered. */
internal val PanelState.isAgentControlOffered: Boolean
    get() = status == LinkStatus.Ready && features?.isAgentControl == true
