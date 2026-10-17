package io.aequicor.heartbeat.feature.browser.impl.domain

/**
 * Which Chromium storage (cookies, local storage, IndexedDB, cache) a tab's pages use, fixed when the tab is created.
 * A tab of an agent session starts in that session's own empty in-memory storage, so a page an agent opens never meets
 * the sites the user is signed in to. The user's own tabs, and tabs the user handed over to an agent, use the profile.
 *
 * Residual risk (issue 445): a handed-over tab keeps the user's storage, and so do the links its page opens. An agent
 * reads it and opens any public address in it without review; only its input is reviewed, and the reviewer is told the
 * tab's storage. The storage is not a network boundary either: CEF does not intercept WebSocket traffic.
 */
internal sealed interface BrowserStorage {
    /** The profile's persistent storage with the user's cookies and signed-in sites. */
    data object User : BrowserStorage

    /**
     * In-memory storage shared only by the tabs of the agent session [owner] (and links they open): never written to
     * disk, empty at start, and discarded with the session's last tab, when browser data is cleared and on exit.
     */
    data class Agent(val owner: String) : BrowserStorage {
        override fun toString(): String = "BrowserStorage.Agent"
    }

    companion object {
        /** The storage a tab created for [owner] starts with; `null` is the user. */
        fun of(owner: String?): BrowserStorage = owner?.let(::Agent) ?: User
    }
}
