package io.aequicor.heartbeat.feature.browser.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
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
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.browser.api.browserAddress
import io.aequicor.heartbeat.feature.browser.impl.di.scope.BrowserScope
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pro.respawn.flowmvi.plugins.reduce
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
    val store = factory.create<BrowserScreenState, BrowserScreenIntent, BrowserScreenAction>(
        name = "Browser",
        initial = BrowserScreenState(hasTabs = surface.hasTabs).reflectState(machine.state.value),
        onError = { copy(error = BrowserScreenError.EngineUnavailable, isLoading = false) },
    ) {
        reflect(machine) { reflectState(it) }
        reduce { intent ->
            when (intent) {
                is BrowserScreenIntent.ApproveControl -> sendTo(
                    machine,
                    controlRequest(intent.tabId, intent.requestId, BrowserOperation.ApproveControl),
                )

                is BrowserScreenIntent.RejectControl -> sendTo(
                    machine,
                    controlRequest(intent.tabId, intent.requestId, BrowserOperation.RejectControl),
                )

                BrowserScreenIntent.ClearData -> sendTo(
                    machine,
                    BrowserIntent.Public.Perform(BrowserRequest(Uuid.random().toString(), BrowserOperation.ClearData)),
                )

                BrowserScreenIntent.NewTab -> withState {
                    sendTo(
                        machine,
                        BrowserIntent.Public.Perform(
                            BrowserRequest(Uuid.random().toString(), BrowserOperation.NewTab, selectedTabId),
                        ),
                    )
                }

                is BrowserScreenIntent.SelectTab ->
                    sendTo(machine, BrowserIntent.Public.SelectTab(intent.id))

                is BrowserScreenIntent.CloseTab ->
                    sendTo(
                        machine,
                        BrowserIntent.Public.Perform(
                            BrowserRequest(
                                Uuid.random().toString(),
                                BrowserOperation.CloseTab,
                                intent.id,
                            ),
                        ),
                    )

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
        url = state.page.url,
        title = state.page.title,
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
