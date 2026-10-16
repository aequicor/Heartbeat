package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserTab
import io.aequicor.heartbeat.feature.browser.api.browserSearchUrl
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.api.normalizeBrowserUrl
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAgentPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRequestKind
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserSession
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserWorkspaceStorage
import io.aequicor.heartbeat.feature.browser.impl.domain.MAX_BROWSER_TABS
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserTab
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserWorkspace
import io.aequicor.heartbeat.feature.browser.impl.domain.chooseBrowserFavicon
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Profile-owned tabs grouped by session owner (null is the personal group). New links inherit the
 * parent group, control state and approved origin, and are inserted immediately after that parent.
 * Tab metadata is saved in profile storage before operations finish and restored on enable, under human
 * control. Disabling releases surfaces without deleting that journal; explicit close/clear updates it.
 * All registry mutations run on main; each tab serializes its native
 * operations. Each tab's [DesktopBrowserAgentGuard] keeps an agent's documents on the public web and the
 * origin its owner approved; what it refuses is reported to the agent with its next operation on the tab.
 * When a page finishes loading, its site icon is looked up and kept with the tab while the tab stays on that origin.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<BrowserSession>(), priority = 1)
@ContributesBinding(ProfileScope::class, binding = binding<BrowserSurface>(), priority = 1)
@Inject
internal class DesktopBrowserSession(
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
    override val nativeResources: BrowserNativeResources,
    private val agentPolicy: BrowserAgentPolicy,
    private val storage: BrowserWorkspaceStorage,
    private val favicons: DesktopBrowserFavicons,
) : BrowserSession,
    BrowserSurface {
    private val log = Log.tag("BrowserWorkspace")
    private val entries = linkedMapOf<String, Entry>()
    private val operations = mutableMapOf<String, Pair<String?, Job>>()
    private val agentOperations = mutableSetOf<String>()

    init {
        scope.coroutineScope.launch(dispatchers.main) {
            agentPolicy.observe().collect { allowed ->
                if (!allowed) agentOperations.toList().forEach { operations[it]?.second?.cancel() }
            }
        }
    }

    private val availabilityLock = Mutex()
    private var isRestoring = false
    private var isBrowserEnabled = false
    private var isClearingData = false
    private var selected: String? = null
    override val pages = MutableStateFlow(BrowserPage())
    override val tabs = MutableStateFlow(emptyList<BrowserTab>() to null as String?)
    override val hasTabs = true
    override val initialUrl: String
        get() = pages.value.url

    private val factory
        get() = nativeResources as DesktopBrowserPanelFactory

    override fun forTab(id: String?): BrowserSurface = entries[id ?: selected]?.surface ?: this

    override fun attached(controller: BrowserViewController) = Unit

    override fun changed(controller: BrowserViewController, state: BrowserViewState) = Unit

    override fun detached(controller: BrowserViewController) = Unit

    override suspend fun setEnabled(isEnabled: Boolean): Unit = withContext(dispatchers.main) {
        availabilityLock.withLock {
            if (isBrowserEnabled == isEnabled) return@withLock
            if (isEnabled) {
                restoreTabs()
                isBrowserEnabled = true
                publish()
            } else {
                // Revocation destroys native surfaces, but must not overwrite the restart journal with an empty list.
                isBrowserEnabled = false
                operations.values.toList().forEach { it.second.cancel() }
                entries.keys.toList().forEach { close(it) }
            }
        }
    }

    private suspend fun restoreTabs() {
        val saved = storage.load().validated()
        isRestoring = true
        var isComplete = false
        try {
            saved.tabs.forEach { create(it.owner, restored = it) }
            selected = saved.selectedTabId
            log.i { "Restored browser workspace: tabs=${entries.size}" }
            isComplete = true
        } finally {
            try {
                // A partial reconstruction never replaces the saved workspace, including on cancellation.
                if (!isComplete) withContext(NonCancellable) { entries.keys.toList().forEach { close(it) } }
            } finally {
                isRestoring = false
            }
        }
    }

    override suspend fun execute(command: BrowserCommand) = withContext(dispatchers.main) {
        if (isBrowserEnabled && !isClearingData) {
            val entry = entries[selected] ?: create(null)
            entry.surface.execute(command)
            entry.update(entry.surface.pages.value)
            publish()
        }
    }

    override suspend fun selectTab(id: String) = withContext(dispatchers.main) {
        if (isBrowserEnabled && !isClearingData && id in entries) {
            selected = id
            publish()
        }
    }

    override suspend fun cancelOperation(id: String) = withContext(dispatchers.main) {
        operations[id]?.second?.cancelAndJoin()
        Unit
    }

    override suspend fun perform(request: BrowserRequest): BrowserResult = withContext(dispatchers.main) {
        check(isBrowserEnabled) { "Browser disabled" }
        operations[request.id] = request.tabId to currentCoroutineContext().job
        if (request.owner != null) agentOperations += request.id
        try {
            check(!isClearingData) { "Browser data is being cleared" }
            if (request.owner != null) {
                check(agentPolicy.isEnabled()) { "Browser tools disabled" }
            }
            val data = dispatch(request)
            log.i { "Browser operation completed: ${request.operation}" }
            data
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) {
                "Browser operation failed: ${request.operation}"
            }
            BrowserResult(request.id, error = operationError(error))
        } finally {
            operations.remove(request.id)
            agentOperations.remove(request.id)
        }
    }

    private fun operationError(error: Exception): String = when (error) {
        is IllegalArgumentException,
        is IllegalStateException,
        -> error.message ?: "Browser operation failed"

        else -> "Browser operation failed"
    }

    private suspend fun dispatch(request: BrowserRequest): BrowserResult {
        if (request.operation == BrowserOperation.ListTabs) return listTabs(request)
        if (request.operation == BrowserOperation.ClearData) return clearData(request)
        if (request.operation == BrowserOperation.RequestControl) return requestControl(request)
        return operate(request)
    }

    /** A tool may only ask; the native tab owner changes after an explicit UI action. */
    private suspend fun requestControl(request: BrowserRequest): BrowserResult {
        val owner = requireNotNull(request.owner) { "An agent session is required" }
        val entry = requireNotNull(entries[request.tabId ?: selected]) { "Select a user tab first" }
        require(entry.tab.owner == null) { "Only a user-owned tab can be requested" }
        require(entry.tab.pendingOwner == null || entry.tab.pendingOwner == owner) {
            "Another session has already requested this tab"
        }
        entry.tab = entry.tab.copy(pendingOwner = owner, controlRequestId = request.id)
        selected = entry.tab.id
        publish()
        return BrowserResult(request.id, buildJsonObject { put("status", "waiting_for_user") })
    }

    private suspend fun clearData(request: BrowserRequest): BrowserResult {
        require(request.owner == null) { "Only the user can clear browser data" }
        isClearingData = true
        try {
            val caller = currentCoroutineContext().job
            val pending = operations.values.map { it.second }.filter { it != caller }
            pending.forEach { it.cancel() }
            pending.joinAll()
            // Remove recovery data before deleting native state; a restart during cleanup cannot resurrect tabs.
            storage.save(SavedBrowserWorkspace())
            entries.keys.toList().forEach { close(it) }
            favicons.clear()
            factory.clearData()
            return BrowserResult(request.id)
        } finally {
            isClearingData = false
        }
    }

    private fun listTabs(request: BrowserRequest): BrowserResult {
        val visible =
            entries.values.filter { request.owner == null || it.tab.owner == request.owner }
        return BrowserResult(
            request.id,
            buildJsonObject {
                put(
                    "tabs",
                    JsonArray(
                        visible.map { entry ->
                            buildJsonObject {
                                put("id", entry.tab.id)
                                put("url", entry.tab.page.url)
                                put("title", entry.tab.page.title)
                                put("humanControlled", entry.tab.isHumanControlled)
                            }
                        },
                    ),
                )
            },
        )
    }

    private suspend fun newTab(request: BrowserRequest): Entry {
        val parent = request.tabId?.let { id ->
            val entry = requireNotNull(entries[id]) { "Unknown tab" }
            require(request.owner == null || entry.tab.owner == request.owner) {
                "Tab belongs to another session"
            }
            require(request.owner == null || !entry.tab.isHumanControlled) {
                "Tab is controlled by the user"
            }
            entry
        }
        return create(request.owner ?: parent?.tab?.owner, parent)
    }

    private suspend fun operate(request: BrowserRequest): BrowserResult {
        val entry =
            if (request.operation == BrowserOperation.NewTab) {
                newTab(request)
            } else {
                requireNotNull(entries[request.tabId]) { "Unknown tab" }
            }
        operations[request.id] = entry.tab.id to currentCoroutineContext().job
        require(request.owner == null || entry.tab.owner == request.owner) {
            "Tab belongs to another session"
        }
        if (request.owner != null) {
            require(!entry.tab.isHumanControlled) { "Tab is controlled by the user" }
        }
        return when (request.operation) {
            BrowserOperation.ApproveControl,
            BrowserOperation.RejectControl,
            BrowserOperation.TakeControl,
            BrowserOperation.ReturnControl,
            -> transfer(request, entry)

            BrowserOperation.CloseTab -> {
                close(entry.tab.id)
                BrowserResult(request.id)
            }

            BrowserOperation.RequestControl,
            BrowserOperation.ListTabs,
            BrowserOperation.NewTab,
            BrowserOperation.Navigate,
            BrowserOperation.Search,
            BrowserOperation.Read,
            BrowserOperation.Screenshot,
            BrowserOperation.Click,
            BrowserOperation.Type,
            BrowserOperation.Key,
            BrowserOperation.Scroll,
            BrowserOperation.Drag,
            BrowserOperation.CaptureResource,
            BrowserOperation.ClearData,
            -> {
                selected = entry.tab.id
                publish()
                entry.lock.withLock {
                    if (request.owner != null) {
                        check(agentPolicy.isEnabled()) { "Browser tools disabled" }
                    }
                    require(isBrowserEnabled && entry.tab.id in entries) { "Tab closed" }
                    require(request.owner == null || !entry.tab.isHumanControlled) {
                        "Tab is controlled by the user"
                    }
                    tabOperation(request, entry)
                }
            }
        }
    }

    private suspend fun transfer(request: BrowserRequest, entry: Entry): BrowserResult {
        require(request.owner == null) { "Only the user can transfer control" }
        val pendingOwner = entry.tab.pendingOwner
        val controlRequestId = entry.tab.controlRequestId
        if (
            request.operation == BrowserOperation.ApproveControl ||
            request.operation == BrowserOperation.RejectControl
        ) {
            require(
                entry.tab.controlRequestId != null &&
                    request.arguments["controlRequestId"]?.jsonPrimitive?.content ==
                    entry.tab.controlRequestId,
            ) {
                "Control request changed; use the current request"
            }
        }
        entry.tab = entry.tab.copy(isHumanControlled = true)
        // Revoke new requests immediately; persistence must never delay cancellation of existing ones.
        // Keep the native guard in place until every operation has finished its cancellation cleanup.
        projectTabs()
        val pending = operations
            .filter { it.key != request.id && it.value.first == entry.tab.id }
            .values
            .map { it.second }
        pending.forEach { it.cancel() }
        pending.joinAll()
        require(entries[entry.tab.id] === entry) { "Tab closed" }
        require(entry.tab.controlRequestId == controlRequestId && entry.tab.pendingOwner == pendingOwner) {
            "Control request changed; use the current request"
        }
        entry.tab =
            when {
                request.operation == BrowserOperation.ApproveControl ->
                    entry.tab.copy(
                        owner = requireNotNull(entry.tab.pendingOwner) { "No pending request" },
                        pendingOwner = null,
                        controlRequestId = null,
                        isHumanControlled = false,
                    )

                request.operation == BrowserOperation.RejectControl ->
                    entry.tab.copy(
                        pendingOwner = null,
                        controlRequestId = null,
                        isHumanControlled = true,
                    )

                else ->
                    entry.tab.copy(
                        isHumanControlled = request.operation == BrowserOperation.TakeControl,
                    )
            }
        // The user hands the agent the tab on its current page, which counts as approving that page's origin; the
        // native page is read directly, as the tab projection may lag behind it.
        entry.syncGuard(approved = entry.surface.pages.value.url)
        publish()
        return result(request, entry)
    }

    private suspend fun tabOperation(request: BrowserRequest, entry: Entry): BrowserResult = when (request.operation) {
        BrowserOperation.CaptureResource -> {
            require(request.owner == null) {
                "Only explicit source recovery can download documents"
            }
            entry.driver.captureResource(request)
        }

        BrowserOperation.NewTab,
        BrowserOperation.Navigate,
        BrowserOperation.Search,
        -> navigate(request, entry)

        BrowserOperation.Read,
        BrowserOperation.Screenshot,
        BrowserOperation.Click,
        BrowserOperation.Type,
        BrowserOperation.Key,
        BrowserOperation.Scroll,
        BrowserOperation.Drag,
        -> withGuardNotes(request, entry, entry.driver.perform(request))

        BrowserOperation.RequestControl,
        BrowserOperation.ApproveControl,
        BrowserOperation.RejectControl,
        BrowserOperation.ListTabs,
        BrowserOperation.CloseTab,
        BrowserOperation.TakeControl,
        BrowserOperation.ReturnControl,
        BrowserOperation.ClearData,
        -> error("Not a native tab operation")
    }

    private suspend fun navigate(request: BrowserRequest, entry: Entry): BrowserResult {
        val address = request.arguments["url"]?.jsonPrimitive?.content
        require(request.operation != BrowserOperation.Navigate || address != null) {
            "URL required"
        }
        val url =
            if (request.operation == BrowserOperation.Search) {
                val query = request.arguments["query"]?.jsonPrimitive?.content.orEmpty()
                require(query.isNotBlank()) { "Empty search query" }
                browserSearchUrl(query)
            } else {
                address?.let { requireNotNull(normalizeBrowserUrl(it)) { "Invalid URL" } }
            }
        if (url != null) {
            entry.driver.invalidate()
            // The dispatcher asks the user before an agent opens a non-public address, so only the owner's own
            // navigation approves its origin; any other address it opens withdraws that approval.
            if (request.owner != null) entry.syncGuard(approved = url)
            entry.guard.consumeRefusal()
            entry.guard.consumeContentRefusal()
            entry.surface.execute(BrowserCommand.Load(url))
            entry.update(entry.surface.pages.value)
            publish()
            if (request.operation != BrowserOperation.NewTab) {
                val loaded =
                    withFailureTimeout(NAVIGATION_TIMEOUT, log, "Page load") {
                        entry.surface.pages.first { !it.isLoading && it.url.isNotEmpty() }
                    }
                if (loaded.error != null && entry.guard.consumeRefusal()) {
                    log.i { "Agent tab navigation blocked" }
                    error(BLOCKED_NAVIGATION)
                }
                check(loaded.error == null) { "Page failed to load" }
            }
        }
        return withGuardNotes(request, entry, result(request, entry))
    }

    /**
     * Tells the agent what its tab's guard stopped since the previous operation: a navigation the page started, or
     * an embedded document (an iframe) of a local or private-network address. The page keeps loading otherwise.
     */
    private fun withGuardNotes(request: BrowserRequest, entry: Entry, result: BrowserResult): BrowserResult {
        if (request.owner == null) return result
        val notes = buildMap {
            if (entry.guard.consumeRefusal()) put("blockedNavigation", JsonPrimitive(BLOCKED_NAVIGATION))
            if (entry.guard.consumeContentRefusal()) put("blockedContent", JsonPrimitive(BLOCKED_CONTENT))
        }
        if (notes.isEmpty()) return result
        log.i { "Agent tab guard blocked: ${notes.keys.joinToString()}" }
        return result.copy(data = JsonObject(result.data + notes))
    }

    /** A session owns one group; a user-created sibling inherits the selected group's authorization. */
    private suspend fun create(owner: String?, parent: Entry? = null, restored: SavedBrowserTab? = null): Entry {
        require(entries.size < MAX_BROWSER_TABS) { "Close a tab before opening another" }
        val id = restored?.id ?: Uuid.random().toString()
        val surface = BrowserSurfaceAdapter(scope, dispatchers, nativeResources)
        surface.setEnabled(true)
        val guard = DesktopBrowserAgentGuard()
        val panel = factory.create(surface, guard) { url -> openLinkedTab(id, url) }
        val entry =
            Entry(
                BrowserTab(
                    id,
                    page = restored?.let { BrowserPage(url = it.url, title = it.title, faviconUrl = it.faviconUrl) }
                        ?: BrowserPage(),
                    owner = owner,
                    isHumanControlled = restored != null || parent?.tab?.isHumanControlled == true,
                ),
                surface,
                DesktopBrowserAutomation(panel::devTools, panel::captureResource),
                guard,
                DesktopBrowserFaviconProbe(panel::devTools),
                panel::loadIcon,
            )
        restored?.takeIf { it.faviconUrl.isNotEmpty() }?.let { entry.favicon = Favicon(it.url, it.faviconUrl) }
        entry.syncGuard(approved = null)
        if (parent != null) guard.inheritFrom(parent.guard)
        val after = parent?.tab?.id ?: entries.values.lastOrNull { it.tab.owner == owner }?.tab?.id
        val ordered = entries.values.toMutableList()
        val index = ordered.indexOfFirst { it.tab.id == after }
        ordered.add(if (index < 0) ordered.size else index + 1, entry)
        entries.clear()
        ordered.forEach { entries[it.tab.id] = it }
        selected = id
        if (!restored?.url.isNullOrEmpty()) surface.execute(BrowserCommand.Load(restored.url))
        entry.observer = observeEntry(entry)
        publish()
        return entry
    }

    @HighFrequency
    private fun observeEntry(entry: Entry): Job = scope.coroutineScope.launch(dispatchers.main) {
        var hasBeenLoading = false
        entry.surface.pages.collect { page ->
            if (entries[entry.tab.id] === entry) {
                followFavicon(entry, page, hasBeenLoading)
                hasBeenLoading = page.isLoading
                entry.update(page)
                try {
                    publish()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error.redactedFailure()) { "Browser workspace could not be saved" }
                    pages.value = pages.value.copy(error = BrowserError.EngineUnavailable)
                }
            }
        }
    }

    /** A new load abandons the previous page's lookup; a finished one looks its icon up. */
    @HighFrequency
    private fun followFavicon(entry: Entry, page: BrowserPage, hasBeenLoading: Boolean) {
        if (page.isLoading) {
            entry.faviconLookup?.cancel()
        } else if (hasBeenLoading && page.error == null) {
            lookUpFavicon(entry, page.url)
        }
    }

    /**
     * Finds the icon of the page that just loaded. A failed read still tries the origin's `/favicon.ico`; an agent's
     * restricted tab accepts only a public icon or one from the page's own origin, as it could load nothing else.
     */
    private fun lookUpFavicon(entry: Entry, pageUrl: String) {
        entry.faviconLookup?.cancel()
        entry.faviconLookup = scope.coroutineScope.launch(dispatchers.main) {
            if (!favicons.isEnabled()) return@launch
            val candidates = try {
                entry.favicons.candidates()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error.redactedFailure()) { "Site icon links could not be read" }
                emptyList()
            }
            val icon = chooseBrowserFavicon(pageUrl, candidates)?.takeIf {
                !entry.guard.isRestricted || isPublicWebUrl(it) || browserOrigin(it) == browserOrigin(pageUrl)
            }
            if (entries[entry.tab.id] !== entry) return@launch
            log.v { "Site icon resolved: found=${icon != null}" }
            entry.favicon = icon?.let { Favicon(pageUrl, it) }
            entry.update(entry.surface.pages.value)
            publish()
            if (icon != null) favicons.load(icon, entry.loadIcon)
        }
    }

    /** Native callbacks may arrive on CEF threads; recheck the live parent on main before inheriting it. */
    private fun openLinkedTab(parentId: String, command: BrowserCommand.Load) {
        val url = command.url
        scope.coroutineScope.launch(dispatchers.main) {
            val parent = entries[parentId] ?: return@launch
            if (!isBrowserEnabled || isClearingData || !isBrowserUrlAllowed(url)) return@launch
            if (!parent.guard.allows(url, BrowserRequestKind.MainDocument)) return@launch
            try {
                val child = create(parent.tab.owner, parent)
                child.surface.execute(command)
                child.update(child.surface.pages.value)
                publish()
                log.i { "Browser link opened in a sibling tab" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error.redactedFailure()) { "Browser linked tab could not be opened" }
                parent.tab = parent.tab.copy(page = parent.tab.page.copy(error = BrowserError.LoadFailed))
                publish()
            }
        }
    }

    private suspend fun close(id: String) {
        val siblings = entries.values.filter { it.tab.owner == entries[id]?.tab?.owner }.map { it.tab.id }
        val index = siblings.indexOf(id)
        val neighbor = siblings.getOrNull(index + 1) ?: siblings.getOrNull(index - 1)
        val entry = entries.remove(id) ?: return
        val caller = currentCoroutineContext().job
        operations
            .filterValues { it.first == id && it.second != caller }
            .values
            .toList()
            .forEach { it.second.cancel() }
        entry.observer?.cancel()
        entry.faviconLookup?.cancel()
        entry.surface.setEnabled(false)
        factory.close(entry.surface)
        if (selected == id) selected = neighbor ?: entries.keys.lastOrNull()
        publish()
    }

    @HighFrequency
    private fun projectTabs() {
        log.v { "Browser tab projection changed" }
        tabs.value = entries.values.groupBy { it.tab.owner }.values.flatten().map { it.tab } to selected
        pages.value = entries[selected]?.tab?.page ?: BrowserPage()
    }

    @HighFrequency
    private suspend fun publish() {
        projectTabs()
        if (isBrowserEnabled && !isRestoring && !isClearingData) {
            storage.save(
                SavedBrowserWorkspace(
                    tabs.value.first.map {
                        SavedBrowserTab(it.id, it.page.url, it.page.title, it.owner, it.page.faviconUrl)
                    },
                    selected,
                ),
            )
        }
    }

    private fun result(request: BrowserRequest, entry: Entry) = BrowserResult(
        request.id,
        buildJsonObject {
            put("tabId", entry.tab.id)
            put("url", entry.surface.pages.value.url)
            put("title", entry.surface.pages.value.title)
        },
    )

    private companion object {
        const val NAVIGATION_TIMEOUT = 30_000L
        const val BLOCKED_NAVIGATION =
            "The page tried to open a local or private-network address and was stopped. " +
                "Open such an address with browser_open so the user can approve it."
        const val BLOCKED_CONTENT =
            "The page embedded a local or private-network address; that content was blocked. " +
                "Open such an address with browser_open so the user can approve it."
    }

    /** The icon found for [pageUrl]; it stays with the tab while the tab shows a page of the same origin. */
    private data class Favicon(val pageUrl: String, val url: String)

    private class Entry(
        var tab: BrowserTab,
        val surface: BrowserSurfaceAdapter,
        val driver: DesktopBrowserAutomation,
        val guard: DesktopBrowserAgentGuard,
        val favicons: DesktopBrowserFaviconProbe,
        val loadIcon: suspend (String) -> ByteArray,
    ) {
        val lock = Mutex()
        var observer: Job? = null
        var faviconLookup: Job? = null
        var favicon: Favicon? = null

        /**
         * Documents of an agent tab are limited to the public web and the origin of [approved]; the
         * user's own tab, or one the user currently controls, is unrestricted.
         */
        fun syncGuard(approved: String?) {
            if (tab.owner == null || tab.isHumanControlled) guard.unrestrict() else guard.restrict(approved)
        }

        @HighFrequency
        fun update(page: BrowserPage) {
            if (page.url != tab.page.url || page.isLoading) driver.invalidate()
            val isPendingTitle = page.isLoading && page.url == tab.page.url
            val title = if (isPendingTitle) page.title.ifBlank { tab.page.title } else page.title
            val icon = favicon?.takeIf { browserOrigin(it.pageUrl) == browserOrigin(page.url) }?.url.orEmpty()
            tab = tab.copy(page = page.copy(title = title, faviconUrl = icon))
        }
    }
}
