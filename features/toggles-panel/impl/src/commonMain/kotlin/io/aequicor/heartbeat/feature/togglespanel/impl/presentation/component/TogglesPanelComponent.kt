package io.aequicor.heartbeat.feature.togglespanel.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import io.aequicor.heartbeat.feature.togglespanel.impl.di.scope.TogglesPanelScope
import io.aequicor.heartbeat.feature.togglespanel.impl.domain.UnifiedSettingsPolicy
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How the panel is shown: as a settings section, on its own, or not yet decided (redirect pending). */
enum class PanelPresentation { Pending, Embedded, Standalone }

/**
 * Lifecycle-bound navigation component rendering the feature screen. Inside the settings window it is embedded;
 * opened elsewhere it moves to the settings section while unified settings are on.
 */
@AssistedInject
class TogglesPanelComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted route: TogglesPanelRoute,
    val model: TogglesPanelModel,
    policy: UnifiedSettingsPolicy,
    @ForScope(TogglesPanelScope::class) scope: ScopeHandle,
) : ComponentContext by context {
    private val log = Log.tag("TogglesPanelComponent")
    private val mutablePresentation = MutableStateFlow(
        if (route.isEmbedded) PanelPresentation.Embedded else PanelPresentation.Pending,
    )

    /** Current presentation; the screen draws its own header only when [PanelPresentation.Standalone]. */
    val presentation: StateFlow<PanelPresentation> = mutablePresentation

    init {
        if (!route.isEmbedded) {
            // The feature scope outlives this entry: stop the redirect if the entry is destroyed first.
            val redirect = scope.coroutineScope.launch {
                if (policy.isUnified()) {
                    log.i { "legacy panel route opens the settings section" }
                    // Open the window, then remove exactly this entry, whatever was pushed meanwhile.
                    navigator.navigate(
                        SettingsRoute(SettingsSection.FeatureFlags),
                        NavOptions(LaunchMode.SingleTop, NavTarget.Nearest, NavTransition.None),
                    )
                    navigator.close()
                } else {
                    mutablePresentation.value = PanelPresentation.Standalone
                }
            }
            lifecycle.doOnDestroy { redirect.cancel() }
        }
    }

    /** Closes this navigation entry. */
    fun close() = navigator.close()

    /** Metro factory for a lifecycle-owned feature instance. */
    @AssistedFactory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun create(context: ComponentContext, navigator: Navigator, route: TogglesPanelRoute): TogglesPanelComponent
    }
}
