package io.aequicor.heartbeat.feature.applogs.impl.presentation

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.applogs.api.AppLogsIntent
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOperation
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOutcome
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOutput
import io.aequicor.heartbeat.feature.applogs.api.AppLogsState
import io.aequicor.heartbeat.feature.applogs.impl.di.scope.AppLogsScope
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

/** UI operations carry no contents or paths through the screen logger. */
enum class AppLogsActionUi { CopyRecent, SaveRecent, SaveArchive, OpenFolder }

/** Feedback rendered by the settings screen. */
enum class AppLogsNoticeUi { Copied, Saved, Opened, Cancelled, Empty, Failure, Disabled }

/** A projection of the feature machine, containing no filesystem data. */
@Immutable
data class AppLogsScreenState(val isBusy: Boolean = false, val notice: AppLogsNoticeUi? = null) : MVIState

/** User actions translated into machine intents by the model. */
sealed interface AppLogsScreenIntent : MVIIntent {
    /** Runs one native action. */
    data class Execute(val action: AppLogsActionUi) : AppLogsScreenIntent

    /** Retries the previous failed action. */
    data object Retry : AppLogsScreenIntent
}

/** This screen reports results in its state. */
sealed interface AppLogsScreenAction : MVIAction

/** Lifecycle-owned FlowMVI projection and intent adapter. */
@SingleIn(AppLogsScope::class)
@Inject
class AppLogsModel(
    machine: Machine<AppLogsState, AppLogsIntent, AppLogsOutput>,
    @ForScope(AppLogsScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    val store = factory.create<AppLogsScreenState, AppLogsScreenIntent, AppLogsScreenAction>(
        "AppLogs",
        machine.state.value.toScreenState(),
        onError = { copy(isBusy = false, notice = AppLogsNoticeUi.Failure) },
    ) {
        reflect(machine) { it.toScreenState() }
        reduce { intent ->
            when (intent) {
                is AppLogsScreenIntent.Execute -> sendTo(
                    machine,
                    AppLogsIntent.Public.Execute(intent.action.operation()),
                )

                AppLogsScreenIntent.Retry -> sendTo(machine, AppLogsIntent.Public.Retry)
            }
        }
    }

    init {
        store.start(scope.coroutineScope)
    }
}

internal fun AppLogsState.toScreenState(): AppLogsScreenState = when (this) {
    is AppLogsState.Working -> AppLogsScreenState(isBusy = true)

    is AppLogsState.Ready -> AppLogsScreenState(
        notice = when (outcome) {
            null -> null

            AppLogsOutcome.Success -> when (operation) {
                AppLogsOperation.CopyRecent -> AppLogsNoticeUi.Copied
                AppLogsOperation.SaveRecent, AppLogsOperation.SaveArchive -> AppLogsNoticeUi.Saved
                AppLogsOperation.OpenFolder -> AppLogsNoticeUi.Opened
                null -> null
            }

            AppLogsOutcome.Cancelled -> AppLogsNoticeUi.Cancelled

            AppLogsOutcome.Empty -> AppLogsNoticeUi.Empty

            AppLogsOutcome.Failure -> AppLogsNoticeUi.Failure

            AppLogsOutcome.Disabled -> AppLogsNoticeUi.Disabled
        },
    )
}

private fun AppLogsActionUi.operation(): AppLogsOperation = when (this) {
    AppLogsActionUi.CopyRecent -> AppLogsOperation.CopyRecent
    AppLogsActionUi.SaveRecent -> AppLogsOperation.SaveRecent
    AppLogsActionUi.SaveArchive -> AppLogsOperation.SaveArchive
    AppLogsActionUi.OpenFolder -> AppLogsOperation.OpenFolder
}
