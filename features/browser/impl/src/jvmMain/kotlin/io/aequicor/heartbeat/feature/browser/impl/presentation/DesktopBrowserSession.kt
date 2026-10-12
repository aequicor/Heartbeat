package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.api.BrowserTab
import io.aequicor.heartbeat.feature.browser.api.browserSearchUrl
import io.aequicor.heartbeat.feature.browser.api.normalizeBrowserUrl
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAgentPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Profile-owned tabs. All registry mutations run on main; each tab serializes its native
 * operations.
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

    override suspend fun setEnabled(isEnabled: Boolean) = withContext(dispatchers.main) {
        isBrowserEnabled = isEnabled
        if (!isEnabled) {
            operations.values.toList().forEach { it.second.cancel() }
            entries.keys.toList().forEach { close(it) }
        }
    }

    override suspend fun execute(command: BrowserCommand) = withContext(dispatchers.main) {
        if (isBrowserEnabled && !isClearingData) {
            val entry = entries[selected] ?: create(null)
            entry.surface.execute(command)
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
            log.w(IllegalStateException(error::class.simpleName)) {
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
    private fun requestControl(request: BrowserRequest): BrowserResult {
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
            entries.keys.toList().forEach { close(it) }
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

    private suspend fun operate(request: BrowserRequest): BrowserResult {
        val entry =
            if (request.operation == BrowserOperation.NewTab) {
                create(request.owner)
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
        publish()
        operations
            .filter { it.key != request.id && it.value.first == entry.tab.id }
            .values
            .toList()
            .forEach { it.second.cancelAndJoin() }
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
        -> entry.driver.perform(request)

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
            entry.surface.execute(BrowserCommand.Load(url))
            if (request.operation != BrowserOperation.NewTab) {
                val loaded =
                    withFailureTimeout(NAVIGATION_TIMEOUT, log, "Page load") {
                        entry.surface.pages.first { !it.isLoading && it.url.isNotEmpty() }
                    }
                check(loaded.error == null) { "Page failed to load" }
            }
        }
        return result(request, entry)
    }

    private suspend fun create(owner: String?): Entry {
        require(entries.size < MAX_TABS) { "Close a tab before opening another" }
        val id = Uuid.random().toString()
        val surface = BrowserSurfaceAdapter(scope, dispatchers, nativeResources)
        surface.setEnabled(true)
        val panel = factory.create(surface)
        val entry =
            Entry(
                BrowserTab(id, owner = owner),
                surface,
                DesktopBrowserAutomation(panel::devTools, panel::captureResource),
            )
        entries[id] = entry
        selected = id
        entry.observer =
            scope.coroutineScope.launch(dispatchers.main) {
                surface.pages.collect { page ->
                    if (entries[id] === entry) {
                        entry.update(page)
                        publish()
                    }
                }
            }
        publish()
        return entry
    }

    private suspend fun close(id: String) {
        val entry = entries.remove(id) ?: return
        val caller = currentCoroutineContext().job
        operations
            .filterValues { it.first == id && it.second != caller }
            .values
            .toList()
            .forEach { it.second.cancel() }
        entry.observer?.cancel()
        entry.surface.setEnabled(false)
        factory.close(entry.surface)
        if (selected == id) selected = entries.keys.lastOrNull()
        publish()
    }

    private fun publish() {
        log.v { "Browser tab projection changed" }
        tabs.value = entries.values.map { it.tab } to selected
        pages.value = entries[selected]?.tab?.page ?: BrowserPage()
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
        const val MAX_TABS = 20
    }

    private class Entry(
        var tab: BrowserTab,
        val surface: BrowserSurfaceAdapter,
        val driver: DesktopBrowserAutomation,
    ) {
        val lock = Mutex()
        var observer: Job? = null

        fun update(page: BrowserPage) {
            if (page.url != tab.page.url || page.isLoading) driver.invalidate()
            tab = tab.copy(page = page)
        }
    }
}
