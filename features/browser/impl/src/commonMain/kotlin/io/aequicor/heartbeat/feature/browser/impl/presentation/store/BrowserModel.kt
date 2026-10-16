package io.aequicor.heartbeat.feature.browser.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.browser.api.BrowserDisplay
import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserSecurity
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.browser.api.browserAddress
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.api.isHumanDecisionAllowed
import io.aequicor.heartbeat.feature.browser.impl.di.scope.BrowserScope
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserShortcut
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.toDomain
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.uuid.Uuid

/** The graph retains input and native adapter while Decompose may recreate the screen component. */
@SingleIn(BrowserScope::class)
@Inject
internal class BrowserModel(
    machine: Machine<BrowserState, BrowserIntent, BrowserOutput>,
    @ForScope(BrowserScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
    val surface: BrowserSurface,
) {
    private val log = Log.tag("BrowserScreen")

    val store = factory.create<BrowserScreenState, BrowserScreenIntent, BrowserScreenAction>(
        name = "Browser",
        initial = BrowserScreenState(hasTabs = surface.hasTabs).reflectState(machine.state.value),
        onError = { copy(error = BrowserScreenError.EngineUnavailable, isLoading = false) },
    ) {
        reflect(machine) { reflectState(it) }
        whileSubscribed(name = "shortcuts") {
            surface.shortcuts.collect { intent(BrowserScreenIntent.Shortcut(it)) }
        }
        whileSubscribed(name = "favicons") {
            surface.favicons.collect { images -> updateState { copy(favicons = images.toImmutableMap()) } }
        }
        reduce { intent ->
            when (intent) {
                BrowserScreenIntent.ToggleErrorDetails -> updateState {
                    copy(isErrorDetailsExpanded = !isErrorDetailsExpanded)
                }

                is BrowserScreenIntent.ProceedCertificate -> sendTo(
                    machine,
                    BrowserIntent.Internal.ProceedCertificate(intent.failure.tabId, intent.failure.failure),
                )

                is BrowserScreenIntent.ReturnToSafety -> sendTo(
                    machine,
                    BrowserIntent.Internal.ReturnToSafety(intent.failure.tabId, intent.failure.failure),
                )

                is BrowserScreenIntent.ApproveControl -> sendTo(
                    machine,
                    controlRequest(intent.tabId, intent.requestId, BrowserOperation.ApproveControl),
                )

                is BrowserScreenIntent.RejectControl -> sendTo(
                    machine,
                    controlRequest(intent.tabId, intent.requestId, BrowserOperation.RejectControl),
                )

                BrowserScreenIntent.ClearData -> {
                    // Clearing browsing data forgets the recently closed addresses too, as in Chromium.
                    updateState { copy(closedTabUrls = persistentListOf()) }
                    sendTo(
                        machine,
                        BrowserIntent.Public.Perform(
                            BrowserRequest(Uuid.random().toString(), BrowserOperation.ClearData),
                        ),
                    )
                }

                BrowserScreenIntent.NewTab -> withState { sendTo(machine, newTab(selectedTabId)) }

                is BrowserScreenIntent.SelectTab ->
                    sendTo(machine, BrowserIntent.Public.SelectTab(intent.id))

                is BrowserScreenIntent.CloseTab -> {
                    updateState { closing(intent.id) }
                    sendTo(machine, closeRequest(intent.id))
                }

                is BrowserScreenIntent.Shortcut -> {
                    // Zoom repeats with every wheel step and is logged by the machine; tab keys are logged here.
                    if (intent.shortcut !is BrowserShortcut.Zoom) log.i { "Browser tab shortcut: ${intent.shortcut}" }
                    var action: BrowserIntent.Public? = null
                    updateState { shortcut(intent.shortcut).also { action = it.second }.first }
                    action?.let { sendTo(machine, it) }
                }

                BrowserScreenIntent.Hide ->
                    sendTo(machine, BrowserIntent.Public.Display(BrowserDisplay.Hidden))

                BrowserScreenIntent.ToggleExpanded ->
                    withState {
                        sendTo(
                            machine,
                            BrowserIntent.Public.Display(
                                if (isExpanded) {
                                    BrowserDisplay.Panel
                                } else {
                                    BrowserDisplay.Expanded
                                },
                            ),
                        )
                    }

                BrowserScreenIntent.ToggleControl ->
                    withState {
                        val tab = tabs.firstOrNull { it.id == selectedTabId }
                        if (tab != null) {
                            sendTo(
                                machine,
                                BrowserIntent.Public.Perform(
                                    BrowserRequest(
                                        Uuid.random().toString(),
                                        if (tab.isHumanControlled) {
                                            BrowserOperation.ReturnControl
                                        } else {
                                            BrowserOperation.TakeControl
                                        },
                                        tab.id,
                                    ),
                                ),
                            )
                        }
                    }

                is BrowserScreenIntent.AddressChanged -> updateState {
                    copy(address = intent.value, isAddressEdited = true)
                }

                BrowserScreenIntent.Open -> {
                    updateState { copy(isAddressEdited = false) }
                    withState {
                        sendTo(
                            machine,
                            BrowserIntent.Public.Open(browserAddress(address) ?: address),
                        )
                    }
                }

                BrowserScreenIntent.Back -> {
                    updateState { copy(address = url, isAddressEdited = false) }
                    sendTo(machine, BrowserIntent.Public.Back)
                }

                BrowserScreenIntent.Forward -> {
                    updateState { copy(address = url, isAddressEdited = false) }
                    sendTo(machine, BrowserIntent.Public.Forward)
                }

                BrowserScreenIntent.Reload -> {
                    updateState { copy(address = url, isAddressEdited = false) }
                    sendTo(machine, BrowserIntent.Public.Reload)
                }

                BrowserScreenIntent.Stop -> sendTo(machine, BrowserIntent.Public.Stop)
            }
        }
    }

    init {
        store.start(scope.coroutineScope)
        scope.coroutineScope.launch { machine.send(BrowserIntent.Public.Start) }
    }
}

/**
 * Remembers the closed tab's address for [BrowserShortcut.ReopenClosedTab]: only the user's own tabs on the web, so a
 * reopened address never moves between the user and an agent session.
 */
private fun BrowserScreenState.closing(id: String): BrowserScreenState {
    val tab = tabs.firstOrNull { it.id == id }
    if (tab == null || tab.groupId != null || !isBrowserUrlAllowed(tab.url)) return this
    return copy(closedTabUrls = (closedTabUrls + tab.url).takeLast(MAX_CLOSED_TABS).toImmutableList())
}

private fun closeRequest(id: String) =
    BrowserIntent.Public.Perform(BrowserRequest(Uuid.random().toString(), BrowserOperation.CloseTab, id))

/**
 * What [shortcut] changes in this state and the machine intent, if any, that carries it out. Closing by key leaves an
 * agent session's tabs alone: after the user's last tab, selection moves on to another group, and a repeated or held
 * key must not cancel an agent's work. A closed tab reopens among the user's own tabs.
 */
internal fun BrowserScreenState.shortcut(shortcut: BrowserShortcut): Pair<BrowserScreenState, BrowserIntent.Public?> =
    when (shortcut) {
        is BrowserShortcut.Zoom -> this to BrowserIntent.Public.Zoom(shortcut.direction.toDomain())

        BrowserShortcut.NewTab -> this to newTab(selectedTabId)

        BrowserShortcut.CloseTab -> tabs.firstOrNull { it.id == selectedTabId && it.groupId == null }
            ?.let { closing(it.id) to closeRequest(it.id) } ?: (this to null)

        BrowserShortcut.ReopenClosedTab -> closedTabUrls.lastOrNull()?.let { url ->
            copy(closedTabUrls = closedTabUrls.dropLast(1).toImmutableList()) to newTab(null, url)
        } ?: (this to null)

        BrowserShortcut.NextTab,
        BrowserShortcut.PreviousTab,
        is BrowserShortcut.SelectTab,
        -> this to shortcutTarget(shortcut)?.let { BrowserIntent.Public.SelectTab(it.id) }
    }

/** The visible tab a switching shortcut selects: neighbors wrap around, a missing position selects nothing. */
private fun BrowserScreenState.shortcutTarget(shortcut: BrowserShortcut): BrowserTabUi? {
    val visible = visibleTabs.ifEmpty { return null }
    val index = visible.indexOfFirst { it.id == selectedTabId }
    return when (shortcut) {
        BrowserShortcut.NextTab -> visible[(index + 1).mod(visible.size)]

        BrowserShortcut.PreviousTab -> visible[(index - 1).mod(visible.size)]

        is BrowserShortcut.SelectTab -> {
            val position = shortcut.position
            if (position == null) visible.last() else visible.getOrNull(position - 1)
        }

        is BrowserShortcut.Zoom,
        BrowserShortcut.NewTab,
        BrowserShortcut.CloseTab,
        BrowserShortcut.ReopenClosedTab,
        -> null
    }
}

private fun newTab(parentId: String?, url: String? = null) = BrowserIntent.Public.Perform(
    BrowserRequest(
        Uuid.random().toString(),
        BrowserOperation.NewTab,
        parentId,
        arguments = buildJsonObject { url?.let { put("url", it) } },
    ),
)

internal fun BrowserScreenState.reflectState(state: BrowserState): BrowserScreenState = when (state) {
    BrowserState.Idle -> copy(phase = BrowserPhase.Preparing)

    is BrowserState.Running -> copy(
        tabs =
            state.tabs
                .map {
                    BrowserTabUi(
                        it.id,
                        it.page.title.ifBlank { it.page.url },
                        it.isHumanControlled,
                        it.owner != null,
                        it.controlRequestId,
                        it.owner,
                        it.page.isLoading,
                        it.page.faviconUrl,
                        it.page.isLoading && it.page.isAwaitingResponse,
                        it.page.url,
                    )
                }
                .toImmutableList(),
        selectedTabId = state.selectedTabId,
        isExpanded = state.display == BrowserDisplay.Expanded,
        phase = state.phase(),
        isAddressEdited = isAddressEdited && selectedTabId == state.selectedTabId,
        address = if (
            (!isAddressEdited || selectedTabId != state.selectedTabId) &&
            state.page.error != BrowserError.InvalidAddress
        ) {
            state.page.url
        } else {
            address
        },
        failure = state.failureUi(),
        security = state.securityUi(),
        isErrorDetailsExpanded = keepsDetails(state),
        url = state.page.url,
        title = state.page.title,
        zoomPercent = state.page.zoomPercent,
        isZoomEnabled = state.isZoomEnabled,
        isLoading = state.page.isLoading,
        isBackAvailable = state.page.isBackAvailable,
        isForwardAvailable = state.page.isForwardAvailable,
        error = when (state.page.error) {
            null -> null
            BrowserError.InvalidAddress -> BrowserScreenError.InvalidAddress
            BrowserError.LoadFailed -> BrowserScreenError.LoadFailed
            BrowserError.UnsupportedAddress -> BrowserScreenError.UnsupportedAddress
            BrowserError.EngineUnavailable -> BrowserScreenError.EngineUnavailable
        },
    )
}

private fun BrowserState.Running.phase(): BrowserPhase = when {
    !isConfigured -> BrowserPhase.Preparing
    isEnabled -> BrowserPhase.Ready
    else -> BrowserPhase.Disabled
}

private fun controlRequest(tabId: String, requestId: String, operation: BrowserOperation) =
    BrowserIntent.Public.Perform(
        BrowserRequest(
            Uuid.random().toString(),
            operation,
            tabId,
            arguments = buildJsonObject { put("controlRequestId", requestId) },
        ),
    )

private fun BrowserState.Running.failureUi(): BrowserFailureUi? {
    val failure = page.failure?.takeIf { areErrorPagesEnabled } ?: return null
    val tabId = selectedTabId ?: return null
    return BrowserFailureUi(tabId, failure, isDecisionAvailable = isHumanDecisionAllowed(tabId))
}

/**
 * The committed page's indicator survives pending loads and address-bar feedback; only a failed document drops it.
 */
private fun BrowserState.Running.securityUi(): BrowserSecurityUi {
    val isFailed = page.failure != null || page.error == BrowserError.LoadFailed ||
        page.error == BrowserError.EngineUnavailable
    if (!areErrorPagesEnabled || isFailed) return BrowserSecurityUi.Unknown
    return when (page.security) {
        BrowserSecurity.Unknown -> BrowserSecurityUi.Unknown
        BrowserSecurity.Https -> BrowserSecurityUi.Https
        BrowserSecurity.Http -> BrowserSecurityUi.Http
        BrowserSecurity.CertificateException -> BrowserSecurityUi.CertificateException
    }
}

private fun BrowserScreenState.keepsDetails(state: BrowserState.Running): Boolean =
    isErrorDetailsExpanded && state.areErrorPagesEnabled &&
        state.selectedTabId == selectedTabId && state.page.failure?.id == failure?.failure?.id
