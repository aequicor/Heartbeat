package io.aequicor.heartbeat.feature.chromiumbridge.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The user's external Chromium browser as the Heartbeat extension exposes it to agent tools (desktop only). Only an
 * extension whose user allowed agent control serves it. The extension keeps agent tabs in a «Heartbeat» tab group,
 * attaches the debugger to owned/handed-over tabs or temporarily for a read-only active-page snapshot, allows fixed
 * DevTools methods and fails a guarded navigation unless [resolveDocument] allows it in time. Ownership, approvals
 * and the navigation policy are the caller's: this contract only transports.
 *
 * Every call fails with [ExternalBrowserException] when no such extension is connected or it refuses.
 */
// One transport capability keeps tab ownership, routing and guarded DevTools calls on the same connection.
@Suppress("TooManyFunctions")
public interface ExternalBrowser {
    /** Whether an agent-capable extension is connected. */
    public val state: StateFlow<ExternalBrowserState>

    /** What happens to controlled tabs. */
    public val events: Flow<ExternalTabEvent>

    /** Pins one trusted agent session to the browser hosting its chat turn, even if that connection disappears. */
    public fun bindSession(owner: String, connectionId: String): AutoCloseable

    /** Whether this session currently belongs to a turn submitted from a browser panel. */
    public fun isExternalSession(owner: String): Boolean = false

    /** Whether a tab belongs to the browser hosting this owner; unbound desktop sessions may use any browser. */
    public fun isTabInSession(owner: String, tabId: String): Boolean = true

    /** The tab the user is looking at in that browser, or `null` (no window, a browser page). */
    public suspend fun activeTab(owner: String? = null): ExternalTab?

    /** Reads the current page without handing its control to the agent; browser consent still applies. */
    public suspend fun readActiveTab(owner: String): JsonObject

    /**
     * Opens a blank tab in the «Heartbeat» group and starts controlling it; [navigate] then loads a page, so the
     * first document already passes the navigation guard of a known tab.
     */
    public suspend fun openTab(owner: String = ""): ExternalTab

    /** Agent-created tabs still in their original group, including across app reconnects. Never includes user tabs. */
    public suspend fun ownedTabs(owner: String): List<ExternalTab> = emptyList()

    /** Reattaches only while the extension still recognizes this owner, tab, group and exact page. */
    public suspend fun recoverTab(owner: String, tabId: String, expectedUrl: String): ExternalTab

    /** Starts controlling an existing tab only while it still shows the approved [expectedUrl]. */
    public suspend fun control(tabId: String, expectedUrl: String): ExternalTab

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

/** A tab of the external browser; [id] is an opaque handle bound to its original browser connection. */
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
