package io.aequicor.heartbeat.feature.welcome.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import io.aequicor.heartbeat.feature.welcome.api.WelcomeDestination
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent
import io.aequicor.heartbeat.feature.welcome.api.WelcomeOutput
import io.aequicor.heartbeat.feature.welcome.api.WelcomeState
import io.aequicor.heartbeat.feature.welcome.impl.di.scope.WelcomeScope
import io.aequicor.heartbeat.feature.welcome.impl.presentation.store.WelcomeModel
import kotlinx.coroutines.launch

/** Lifecycle-bound navigation coordinator independent of Compose rendering. */
@AssistedInject
class WelcomeComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    val model: WelcomeModel,
    machine: Machine<WelcomeState, WelcomeIntent, WelcomeOutput>,
    @ForScope(WelcomeScope::class) scope: ScopeHandle,
) : ComponentContext by context {
    init {
        val navigation = scope.coroutineScope.launch {
            machine.state.collect { state ->
                if (state is WelcomeState.Opening) {
                    val route = when (state.destination) {
                        WelcomeDestination.Studio -> AiStudioRoute
                        WelcomeDestination.Toggles -> TogglesPanelRoute
                    }
                    navigator.navigate(
                        route,
                        NavOptions(LaunchMode.SingleTop, NavTarget.Root, NavTransition.Fade),
                    )
                    machine.send(WelcomeIntent.Internal.NavigationHandled)
                }
            }
        }
        lifecycle.doOnDestroy { navigation.cancel() }
        lifecycle.doOnResume(isOneTime = false) {
            if (machine.state.value == WelcomeState.Away) {
                scope.coroutineScope.launch { machine.send(WelcomeIntent.Internal.Returned) }
            }
        }
    }

    /** Metro factory for a lifecycle-owned feature instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun create(context: ComponentContext, navigator: Navigator): WelcomeComponent
    }
}
