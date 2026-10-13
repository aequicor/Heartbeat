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
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTab
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTabEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * extension's debugger. Switching agent control off releases everything. Connection revocations discard stale
 * handles; listing can reacquire agent-created tabs whose owner and original group the extension still recognizes.
 * Initial availability snapshots never revoke ownership: only authoritative tab events do.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ExternalBrowserSession(
    private val browser: ExternalBrowser,
    policy: ExternalBrowserAgentPolicy,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ExternalBrowserControl {
    private val log = Log.tag("ExternalBrowserSession")
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val reservations = mutableMapOf<String, Boolean>()
    private val retiring = mutableSetOf<String>()
    private var pendingNew = 0
    private var generation = 0L
    private var isEnabled = false
    private val policyReady = CompletableDeferred<Unit>()

    init {
        profile.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { browser.events.collect(::onEvent) }
        profile.coroutineScope.launch {
            policy.availability.collect { enabled ->
                lock.withLock { isEnabled = enabled }
                policyReady.complete(Unit)
                if (!enabled) releaseAll()
            }
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
        return perform(entry, page, owner, args, id)
    }

    private suspend fun manage(owner: String, operation: ExternalOperation, args: JsonObject): JsonObject =
        when (operation) {
            ExternalOperation.Tabs -> tabs(owner)

            ExternalOperation.ActiveTab -> browser.readActiveTab(owner)

            ExternalOperation.NewTab -> newTab(owner, args.text("url"))

            ExternalOperation.RequestTab -> requestTab(owner, args)

            ExternalOperation.CloseTab -> release(owned(owner, args), close = true)

            ExternalOperation.Release -> release(owned(owner, args), close = false)

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
        lock.withLock { entries[tabId]?.takeIf { it.owner == owner && browser.isTabInSession(owner, tabId) }?.url }

    private suspend fun tabs(owner: String): JsonObject {
        policyReady.await()
        lock.withLock { checkEnabled() }
        // The extension proves ownership by tab id and original group, never by the visible group title.
        for (tab in browser.ownedTabs(owner)) {
            val isMissing = lock.withLock { tab.id !in entries }
            if (isMissing) acquire(owner, tab, isUserTab = false)
        }
        val own = lock.withLock { entries.values.filter { it.owner == owner && browser.isTabInSession(owner, it.id) } }
        val active = browser.activeTab(owner)
        return buildJsonObject {
            active?.let {
                put(
                    "activeTab",
                    buildJsonObject {
                        put("tabId", it.id)
                        put("url", it.url)
                        put("title", it.title)
                        put("isControlled", own.any { entry -> entry.id == it.id })
                    },
                )
            }
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
        val entry = acquire(owner, expected = null)
        log.i { "External agent tab opened" }
        return if (url == null) describe(entry) else open(entry, url)
    }

    private suspend fun requestTab(owner: String, args: JsonObject): JsonObject {
        val active = browser.activeTab(owner) ?: error("No web page is open in the external browser")
        check(args.text(ApprovedTab.ID) == active.id && args.text(ApprovedTab.URL) == active.url) {
            "The active tab changed since the approval; ask again"
        }
        val entry = acquire(owner, active)
        log.i { "External user tab handed to an agent" }
        return describe(entry)
    }

    private suspend fun acquire(owner: String, expected: ExternalTab?, isUserTab: Boolean = expected != null): Entry {
        policyReady.await()
        val reservation = reserve(owner, expected)
        try {
            reservation.previous?.mutex?.lock()
            reservation.markOperationLocked()
            reservation.previous?.let { checkLive(it) }
            // Keep the bounded acquisition alive until its tab id is known, so cancellation can release it.
            withContext(NonCancellable) {
                val tab = when {
                    expected == null -> browser.openTab(owner)
                    isUserTab -> browser.control(expected.id, expected.url)
                    else -> browser.recoverTab(owner, expected.id, expected.url)
                }
                reservation.recordTab(tab)
            }
            currentCoroutineContext().ensureActive()
            return commitAcquisition(reservation, isUserTab)
        } finally {
            withContext(NonCancellable) { finishAcquisition(reservation) }
        }
    }

    private suspend fun reserve(owner: String, expected: ExternalTab?): Acquisition = lock.withLock {
        checkEnabled()
        val existing = expected?.let { entries[it.id] }
        check(existing == null || existing.owner == owner) { "Another chat controls this tab" }
        check(expected == null || (expected.id !in reservations && expected.id !in retiring)) {
            "Tab handover already in progress"
        }
        require(existing != null || entries.size + pendingNew + reservations.size < MAX_TABS) {
            "Release or close a tab before opening another"
        }
        if (expected == null) pendingNew++ else reservations[expected.id] = true
        Acquisition(owner, expected, generation, existing)
    }

    private suspend fun commitAcquisition(reservation: Acquisition, isUserTab: Boolean): Entry {
        val tab = checkNotNull(reservation.acquired)
        val expected = reservation.expected
        check(expected == null || (tab.id == expected.id && tab.url == expected.url)) {
            "The active tab changed since the approval; ask again"
        }
        val entry = reservation.previous ?: createEntry(reservation.owner, tab, isUserTab = isUserTab)
        lock.withLock {
            checkEnabled()
            check(generation == reservation.generation && (expected == null || reservations[expected.id] == true)) {
                "Tab control ended during acquisition"
            }
            check(reservation.previous == null || entries[tab.id] === entry) { "Tab control ended during acquisition" }
            check(entries[tab.id] == null || entries[tab.id] === entry) { "Another chat controls this tab" }
            entry.update(tab)
            if (expected != null) entry.guard.restrict(expected.url)
            entries[tab.id] = entry
            reservation.commit()
        }
        return entry
    }

    private suspend fun finishAcquisition(reservation: Acquisition) = withContext(NonCancellable) {
        try {
            val isCleanupNeeded = lock.withLock {
                val previous = reservation.previous
                previous == null || entries[previous.id] !== previous
            }
            if (!reservation.isCommitted && isCleanupNeeded) reservation.acquired?.let { releaseAcquired(it.id) }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    val expected = reservation.expected
                    if (expected == null) pendingNew-- else reservations.remove(expected.id)
                }
                if (reservation.isOperationLocked) reservation.previous?.mutex?.unlock()
            }
        }
    }

    /** An ownership claim kept until its remote acquisition and any rollback have finished. */
    private class Acquisition(
        val owner: String,
        val expected: ExternalTab?,
        val generation: Long,
        val previous: Entry?,
    ) {
        var acquired: ExternalTab? = null
            private set
        var isCommitted = false
            private set
        var isOperationLocked = false
            private set

        fun markOperationLocked() {
            isOperationLocked = previous != null
        }

        fun recordTab(tab: ExternalTab) {
            acquired = tab
        }

        fun commit() {
            isCommitted = true
        }
    }

    private fun checkEnabled() {
        check(isEnabled && !profile.isClosed && profile.coroutineScope.isActive) { "Agent control is disabled" }
    }

    private suspend fun releaseAcquired(tabId: String) {
        lock.withLock {
            retiring.add(tabId)
            if (tabId in reservations) reservations[tabId] = false
        }
        try {
            browser.release(tabId)
        } catch (e: ExternalBrowserException) {
            log.w(e) { "External tab acquisition cleanup failed" }
        } finally {
            withContext(NonCancellable) { lock.withLock { retiring.remove(tabId) } }
        }
    }

    private suspend fun release(entry: Entry, close: Boolean): JsonObject = entry.mutex.withLock {
        lock.withLock {
            checkEnabled()
            check(entries[entry.id] === entry) { "Tab control ended; use ex_control_tabs" }
            retiring.add(entry.id)
            entries.remove(entry.id)
        }
        withContext(NonCancellable) {
            try {
                if (close) browser.close(entry.id) else browser.release(entry.id)
            } finally {
                withContext(NonCancellable) { lock.withLock { retiring.remove(entry.id) } }
            }
        }
        closed(entry.id)
    }

    private suspend fun open(entry: Entry, url: String): JsonObject = entry.mutex.withLock {
        checkLive(entry)
        val address = requireNotNull(normalizeBrowserUrl(url)) { "Invalid address" }
        // Guard answers use only the registry lock, never this operation mutex.
        entry.guard.restrict(address)
        entry.driver.invalidate()
        val tab = browser.navigate(entry.id, address)
        checkLive(entry)
        entry.update(tab)
        withGuardNotes(entry, describe(entry))
    }

    private suspend fun perform(
        entry: Entry,
        operation: BrowserOperation,
        owner: String,
        args: JsonObject,
        id: String,
    ): Outcome {
        val result = entry.mutex.withLock {
            checkLive(entry)
            if (operation == BrowserOperation.Screenshot) browser.activate(entry.id)
            entry.driver.perform(BrowserRequest(id, operation, entry.id, owner, args)).also { checkLive(entry) }
        }
        result.error?.let(::error)
        return Outcome(withGuardNotes(entry, result.data), result.imageBase64)
    }

    private fun createEntry(owner: String, tab: ExternalTab, isUserTab: Boolean): Entry {
        val guard = DesktopBrowserAgentGuard().apply { restrict(null) }
        val token = Any()
        val driver = DesktopBrowserAutomation({ method, params ->
            checkLive(tab.id, token)
            browser.devTools(tab.id, method, params).also { checkLive(tab.id, token) }
        })
        return Entry(tab.id, owner, isUserTab, driver, guard, token).apply { update(tab) }
    }

    private suspend fun checkLive(entry: Entry) = checkLive(entry.id, entry.token)

    private suspend fun checkLive(tabId: String, token: Any) = lock.withLock {
        checkEnabled()
        val entry = entries[tabId]
        check(entry == null || browser.isTabInSession(entry.owner, tabId)) { "Tab belongs to another browser" }
        check(entry?.token === token) { "Tab control ended; use ex_control_tabs" }
    }

    private suspend fun owned(owner: String, args: JsonObject): Entry {
        val tabId = requireNotNull(args.text("tabId")) { "tabId required" }
        val entry = lock.withLock { entries[tabId] }
        require(entry != null && entry.owner == owner && browser.isTabInSession(owner, tabId)) {
            "Unknown tab; use ex_control_tabs"
        }
        return entry
    }

    private suspend fun onEvent(event: ExternalTabEvent) {
        val entry = lock.withLock { entries[event.tabId] }
        when (event) {
            is ExternalTabEvent.Changed -> {
                entry?.update(ExternalTab(event.tabId, event.url, event.title))
                entry?.driver?.invalidate()
            }

            is ExternalTabEvent.Removed, is ExternalTabEvent.Revoked -> {
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
        lock.withLock {
            entries.remove(tabId)
            if (tabId in reservations) reservations[tabId] = false
        }
    }

    private suspend fun releaseAll() {
        val released = lock.withLock {
            generation++
            entries.keys.toList().also {
                retiring.addAll(it)
                entries.clear()
            }
        }
        released.forEach { tabId ->
            try {
                browser.release(tabId)
            } catch (e: ExternalBrowserException) {
                log.w(e) { "External tab could not be released" }
            } finally {
                withContext(NonCancellable) { lock.withLock { retiring.remove(tabId) } }
            }
        }
        if (released.isNotEmpty()) log.i { "External agent tabs released count=${released.size}" }
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
        val token: Any,
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
