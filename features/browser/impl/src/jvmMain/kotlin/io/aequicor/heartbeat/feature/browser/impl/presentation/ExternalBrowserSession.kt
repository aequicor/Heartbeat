package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.normalizeBrowserUrl
import io.aequicor.heartbeat.feature.browser.impl.domain.ApprovedTab
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalBrowserAgentPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalBrowserControl
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalOperation
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserException
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTab
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTabEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * The tabs agents drive in the user's external browser. Each tab belongs to the session that opened it or that the
 * user handed it to; other sessions never see it. A tab keeps the agent guard of the embedded browser: the public
 * web plus, at most, the origin the user approved (the address an approved `ex_control_open` loads, or the page a
 * handed-over tab shows). The extension pauses every document load of a controlled tab until [ExternalBrowser]
 * receives this guard's answer. Page reading and input reuse the embedded browser's DevTools driver over the
 * extension's debugger. Switching agent control off or losing the browser releases everything.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ExternalBrowserSession(
    private val browser: ExternalBrowser,
    policy: ExternalBrowserAgentPolicy,
    @ForScope(ProfileScope::class) profile: ScopeHandle,
) : ExternalBrowserControl {
    private val log = Log.tag("ExternalBrowserSession")
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    init {
        profile.coroutineScope.launch { browser.events.collect(::onEvent) }
        profile.coroutineScope.launch {
            browser.state.collect { if (it is ExternalBrowserState.Unavailable) forgetAll("browser unavailable") }
        }
        profile.coroutineScope.launch {
            policy.availability.collect { isEnabled -> if (!isEnabled) releaseAll() }
        }
    }

    override suspend fun execute(owner: String, operation: ExternalOperation, arguments: JsonObject): BrowserResult {
        val requestId = Uuid.random().toString()
        return try {
            val outcome = run(owner, operation, arguments, requestId)
            BrowserResult(requestId, outcome.data, imageBase64 = outcome.image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExternalBrowserException) {
            log.w(e) { "External browser refused operation=$operation failure=${e.failure}" }
            BrowserResult(requestId, JsonObject(emptyMap()), error = e.message)
        } catch (e: IllegalArgumentException) {
            log.w(e.redactedFailure()) { "External browser operation rejected operation=$operation" }
            BrowserResult(requestId, JsonObject(emptyMap()), error = e.message ?: "Invalid arguments")
        } catch (e: IllegalStateException) {
            log.w(e.redactedFailure()) { "External browser operation failed operation=$operation" }
            BrowserResult(requestId, JsonObject(emptyMap()), error = e.message ?: "Browser operation failed")
        }
    }

    private suspend fun run(owner: String, operation: ExternalOperation, args: JsonObject, id: String): Outcome {
        val page = PAGE_OPERATIONS[operation] ?: return Outcome(manage(owner, operation, args))
        val entry = owned(owner, args)
        // A screenshot needs a visible tab.
        if (page == BrowserOperation.Screenshot) browser.activate(entry.id)
        return perform(entry, page, owner, args, id)
    }

    private suspend fun manage(owner: String, operation: ExternalOperation, args: JsonObject): JsonObject =
        when (operation) {
            ExternalOperation.Tabs -> tabs(owner)

            ExternalOperation.NewTab -> newTab(owner, args.text("url"))

            ExternalOperation.RequestTab -> requestTab(owner, args)

            ExternalOperation.CloseTab -> owned(owner, args).let { entry ->
                forget(entry.id)
                browser.close(entry.id)
                closed(entry.id)
            }

            ExternalOperation.Release -> owned(owner, args).let { entry ->
                forget(entry.id)
                browser.release(entry.id)
                closed(entry.id)
            }

            ExternalOperation.Open -> open(owned(owner, args), requireNotNull(args.text("url")) { "url required" })

            ExternalOperation.Read,
            ExternalOperation.Screenshot,
            ExternalOperation.Click,
            ExternalOperation.Type,
            ExternalOperation.Key,
            ExternalOperation.Scroll,
            ExternalOperation.Drag,
            -> error("Not a tab operation")
        }

    override suspend fun address(owner: String, tabId: String): String? =
        lock.withLock { entries[tabId]?.takeIf { it.owner == owner }?.url }

    private suspend fun tabs(owner: String): JsonObject {
        val own = lock.withLock { entries.values.filter { it.owner == owner } }
        return buildJsonObject {
            put(
                "tabs",
                buildJsonArray {
                    own.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("tabId", entry.id)
                                put("url", entry.url)
                                put("title", entry.title)
                                put("isUserTab", entry.isUserTab)
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun newTab(owner: String, url: String?): JsonObject {
        require(lock.withLock { entries.size } < MAX_TABS) { "Release or close a tab before opening another" }
        val tab = browser.openTab()
        val entry = register(owner, tab, isUserTab = false)
        log.i { "External agent tab opened" }
        return if (url == null) describe(entry) else open(entry, url)
    }

    private suspend fun requestTab(owner: String, args: JsonObject): JsonObject {
        val active = browser.activeTab() ?: error("No web page is open in the external browser")
        // The user approved one tab showing one page; anything else needs a new approval.
        check(args.text(ApprovedTab.ID) == active.id && args.text(ApprovedTab.URL) == active.url) {
            "The active tab changed since the approval; ask again"
        }
        val existing = lock.withLock { entries[active.id] }
        check(existing == null || existing.owner == owner) { "Another chat controls this tab" }
        val tab = browser.control(active.id)
        val entry = existing ?: register(owner, tab, isUserTab = true)
        // Handing a tab over approves the page it shows, as in the embedded browser.
        entry.guard.restrict(tab.url)
        log.i { "External user tab handed to an agent" }
        return describe(entry)
    }

    private suspend fun open(entry: Entry, url: String): JsonObject {
        val address = requireNotNull(normalizeBrowserUrl(url)) { "Invalid address" }
        // An approved ex_control_open of a private address approves its origin for this tab.
        entry.guard.restrict(address)
        val tab = entry.mutex.withLock {
            entry.driver.invalidate()
            browser.navigate(entry.id, address)
        }
        entry.update(tab)
        return withGuardNotes(entry, describe(entry))
    }

    private suspend fun perform(
        entry: Entry,
        operation: BrowserOperation,
        owner: String,
        args: JsonObject,
        id: String,
    ): Outcome {
        val result = entry.mutex.withLock { entry.driver.perform(BrowserRequest(id, operation, entry.id, owner, args)) }
        result.error?.let(::error)
        return Outcome(withGuardNotes(entry, result.data), result.imageBase64)
    }

    private suspend fun register(owner: String, tab: ExternalTab, isUserTab: Boolean): Entry {
        val guard = DesktopBrowserAgentGuard().apply { restrict(null) }
        val driver = DesktopBrowserAutomation({ method, params -> browser.devTools(tab.id, method, params) })
        val entry = Entry(tab.id, owner, isUserTab, driver, guard).apply { update(tab) }
        lock.withLock { entries[tab.id] = entry }
        return entry
    }

    private suspend fun owned(owner: String, args: JsonObject): Entry {
        val tabId = requireNotNull(args.text("tabId")) { "tabId required" }
        val entry = lock.withLock { entries[tabId] }
        require(entry != null && entry.owner == owner) { "Unknown tab; use ex_control_tabs" }
        return entry
    }

    private suspend fun onEvent(event: ExternalTabEvent) {
        val entry = lock.withLock { entries[event.tabId] }
        when (event) {
            is ExternalTabEvent.Changed -> {
                entry?.update(ExternalTab(event.tabId, event.url, event.title))
                entry?.driver?.invalidate()
            }

            is ExternalTabEvent.Removed, is ExternalTabEvent.Revoked -> if (entry != null) {
                forget(event.tabId)
                log.i { "External tab left agent control reason=${event::class.simpleName.orEmpty()}" }
            }

            is ExternalTabEvent.DocumentRequested -> {
                val kind = if (event.isMainFrame) BrowserRequestKind.MainDocument else BrowserRequestKind.ChildDocument
                val isAllowed = entry?.guard?.allows(event.url, kind) ?: false
                try {
                    browser.resolveDocument(event.tabId, event.requestId, isAllowed)
                } catch (e: ExternalBrowserException) {
                    // The extension fails an unanswered load on its own; the agent learns it from the page.
                    log.w(e) { "Navigation guard answer lost" }
                }
            }
        }
    }

    /** What the guard stopped since the previous operation of this tab. */
    private fun withGuardNotes(entry: Entry, data: JsonObject): JsonObject {
        val notes = buildMap {
            if (entry.guard.consumeRefusal()) put("blockedNavigation", JsonPrimitive(BLOCKED_NAVIGATION))
            if (entry.guard.consumeContentRefusal()) put("blockedContent", JsonPrimitive(BLOCKED_CONTENT))
        }
        if (notes.isNotEmpty()) log.i { "External tab guard blocked: ${notes.keys.joinToString()}" }
        return JsonObject(data + notes + ("tabId" to JsonPrimitive(entry.id)))
    }

    private fun describe(entry: Entry): JsonObject = buildJsonObject {
        put("tabId", entry.id)
        put("url", entry.url)
        put("title", entry.title)
        put("isUserTab", entry.isUserTab)
    }

    private fun closed(tabId: String): JsonObject = buildJsonObject {
        put("tabId", tabId)
        put("released", true)
    }

    private suspend fun forget(tabId: String) {
        lock.withLock { entries.remove(tabId) }
    }

    private suspend fun releaseAll() {
        val released = lock.withLock { entries.keys.toList().also { entries.clear() } }
        released.forEach { tabId ->
            try {
                browser.release(tabId)
            } catch (e: ExternalBrowserException) {
                log.w(e) { "External tab could not be released" }
            }
        }
        if (released.isNotEmpty()) log.i { "External agent tabs released count=${released.size}" }
    }

    private suspend fun forgetAll(reason: String) {
        val count = lock.withLock { entries.size.also { entries.clear() } }
        if (count > 0) log.i { "External agent tabs forgotten count=$count reason=$reason" }
    }

    /** Result data of an operation and the screenshot it took, if any. */
    private data class Outcome(val data: JsonObject, val image: String? = null)

    /** One controlled tab. */
    private class Entry(
        val id: String,
        val owner: String,
        val isUserTab: Boolean,
        val driver: DesktopBrowserAutomation,
        val guard: DesktopBrowserAgentGuard,
    ) {
        val mutex = Mutex()

        @Volatile
        var url: String = ""
            private set

        @Volatile
        var title: String = ""
            private set

        fun update(tab: ExternalTab) {
            url = tab.url
            title = tab.title
        }
    }

    private companion object {
        const val MAX_TABS = 20
        val PAGE_OPERATIONS = mapOf(
            ExternalOperation.Read to BrowserOperation.Read,
            ExternalOperation.Screenshot to BrowserOperation.Screenshot,
            ExternalOperation.Click to BrowserOperation.Click,
            ExternalOperation.Type to BrowserOperation.Type,
            ExternalOperation.Key to BrowserOperation.Key,
            ExternalOperation.Scroll to BrowserOperation.Scroll,
            ExternalOperation.Drag to BrowserOperation.Drag,
        )
        const val BLOCKED_NAVIGATION =
            "The page tried to open a local or private-network address and was stopped. " +
                "Open such an address with ex_control_open so the user can approve it."
        const val BLOCKED_CONTENT =
            "The page embedded a local or private-network address; that content was blocked. " +
                "Open such an address with ex_control_open so the user can approve it."

        fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content
    }
}
