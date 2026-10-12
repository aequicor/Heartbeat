package io.aequicor.heartbeat.feature.chromiumbridge.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The user's external Chromium browser as the Heartbeat extension exposes it to agent tools (desktop only). Only an
 * extension whose user allowed agent control serves it. The extension keeps agent tabs in a «Heartbeat» tab group,
 * attaches the debugger only to tabs it opened for the agent or the user handed over, allows a fixed set of DevTools
 * methods and fails a guarded navigation unless [resolveDocument] allows it in time. Ownership, approvals and the
 * navigation policy are the caller's: this contract only transports.
 *
 * Every call fails with [ExternalBrowserException] when no such extension is connected or it refuses.
 */
public interface ExternalBrowser {
    /** Whether an agent-capable extension is connected. */
    public val state: StateFlow<ExternalBrowserState>

    /** What happens to controlled tabs. */
    public val events: Flow<ExternalTabEvent>

    /** The tab the user is looking at in that browser, or `null` (no window, a browser page). */
    public suspend fun activeTab(): ExternalTab?

    /**
     * Opens a blank tab in the «Heartbeat» group and starts controlling it; [navigate] then loads a page, so the
     * first document already passes the navigation guard of a known tab.
     */
    public suspend fun openTab(): ExternalTab

    /** Starts controlling an existing tab the user handed over. */
    public suspend fun control(tabId: String): ExternalTab

    /** Loads [url] in a controlled tab and waits until the navigation committed. */
    public suspend fun navigate(tabId: String, url: String): ExternalTab

    /** Brings a controlled tab to the front of its window (screenshots need a visible tab). */
    public suspend fun activate(tabId: String)

    /** Stops controlling a tab; the tab stays open. */
    public suspend fun release(tabId: String)

    /** Closes a controlled tab. */
    public suspend fun close(tabId: String)

    /** Sends one DevTools [method] to a controlled tab and returns its result as JSON text. */
    public suspend fun devTools(tabId: String, method: String, params: JsonObject): String

    /** Answers [ExternalTabEvent.DocumentRequested]; an unanswered request fails after a short timeout. */
    public suspend fun resolveDocument(tabId: String, requestId: String, isAllowed: Boolean)
}

/** Availability of the external browser. */
public sealed interface ExternalBrowserState {
    /** No extension allowing agent control is connected. */
    public data object Unavailable : ExternalBrowserState

    /** The extension of [brand] [version] serves agent control. */
    public data class Connected(public val brand: String, public val version: String) : ExternalBrowserState
}

/** A tab of the external browser; [id] is the browser's own tab id. */
public data class ExternalTab(public val id: String, public val url: String, public val title: String)

/** Something happened to a controlled tab. */
public sealed interface ExternalTabEvent {
    /** The tab the event is about. */
    public val tabId: String

    /** The tab loaded or changed its address or title. */
    public data class Changed(
        override val tabId: String,
        public val url: String,
        public val title: String,
        public val isLoading: Boolean,
    ) : ExternalTabEvent

    /** The tab was closed. */
    public data class Removed(override val tabId: String) : ExternalTabEvent

    /** The user took the tab back (debugger infobar, extension panel); it is no longer controlled. */
    public data class Revoked(override val tabId: String) : ExternalTabEvent

    /** The tab is about to load a document; it waits for [ExternalBrowser.resolveDocument]. */
    public data class DocumentRequested(
        override val tabId: String,
        public val requestId: String,
        public val url: String,
        public val isMainFrame: Boolean,
    ) : ExternalTabEvent
}

/** Why an external browser call failed. */
public enum class ExternalBrowserFailure { Unavailable, Forbidden, NoSuchTab, Timeout, Failed }

/** A refused or failed external browser call; [message] carries no page content. */
public class ExternalBrowserException(
    public val failure: ExternalBrowserFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
