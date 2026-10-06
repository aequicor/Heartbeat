package io.aequicor.heartbeat.feature.browser.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserOutput
import io.aequicor.heartbeat.feature.browser.api.BrowserState
import io.aequicor.heartbeat.feature.browser.impl.di.scope.BrowserScope
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.plugins.reduce

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
        initial = BrowserScreenState().reflectState(machine.state.value),
        onError = { copy(error = BrowserScreenError.EngineUnavailable, isLoading = false) },
    ) {
        reflect(machine) { reflectState(it) }
        reduce { intent ->
            when (intent) {
                is BrowserScreenIntent.AddressChanged -> updateState {
                    copy(address = intent.value, isAddressEdited = true)
                }

                BrowserScreenIntent.Open -> {
                    updateState { copy(isAddressEdited = false) }
                    withState { sendTo(machine, BrowserIntent.Public.Open(address)) }
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
        phase = when {
            !state.isConfigured -> BrowserPhase.Preparing
            state.isEnabled -> BrowserPhase.Ready
            else -> BrowserPhase.Disabled
        },
        address = if (!isAddressEdited && state.page.error != BrowserError.InvalidAddress) state.page.url else address,
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
