package io.aequicor.heartbeat.feature.searchengine.impl.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.secrets.SecretStorageInfo
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.searchengine.api.SearchConfiguration
import io.aequicor.heartbeat.feature.searchengine.impl.domain.UnifiedSettingsPolicy
import io.aequicor.heartbeat.feature.settings.api.SettingsRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How the screen is shown: as a settings section, on its own, or not yet decided (redirect pending). */
internal enum class SearchSettingsPresentation { Pending, Embedded, Standalone }

/**
 * Profile route that owns only screen lifetime; the search service and credentials remain profile-owned.
 * The model lives in the retained [screen] scope: it survives configuration changes and its coroutines are
 * cancelled when the screen scope closes with the component. Opened outside the settings window it moves to the
 * search section while unified settings are on.
 */
@AssistedInject
internal class ProfileSettingsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    @Assisted route: ProfileSettingsRoute,
    configuration: SearchConfiguration,
    factory: HeartbeatStoreFactory,
    storage: SecretStorageInfo,
    policy: UnifiedSettingsPolicy,
) : ComponentContext by context {
    private val log = Log.tag("ProfileSettingsComponent")
    private val mutablePresentation = MutableStateFlow(
        if (route.isEmbedded) SearchSettingsPresentation.Embedded else SearchSettingsPresentation.Pending,
    )

    /** The screen store, retained across configuration changes. */
    val model: SearchSettingsModel = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(SearchSettingsModel(configuration, factory, screen.coroutineScope, storage.protection))
    }.model

    /** Current presentation; the screen draws its own header only when standalone. */
    val presentation: StateFlow<SearchSettingsPresentation> = mutablePresentation

    init {
        if (!route.isEmbedded) {
            screen.coroutineScope.launch {
                if (policy.isUnified()) {
                    log.i { "legacy profile settings route opens the settings section" }
                    // Open the window, then remove exactly this entry, whatever was pushed meanwhile.
                    navigator.navigate(
                        SettingsRoute(SettingsSection.Search),
                        NavOptions(LaunchMode.SingleTop, NavTarget.Nearest, NavTransition.None),
                    )
                    navigator.close()
                } else {
                    mutablePresentation.value = SearchSettingsPresentation.Standalone
                }
            }
        }
    }

    /** Closes the standalone screen. */
    fun close() = navigator.close()

    private class RetainedModel(val model: SearchSettingsModel) : InstanceKeeper.Instance

    @AssistedFactory
    fun interface Factory {
        fun create(
            context: ComponentContext,
            navigator: Navigator,
            screen: ScopeHandle,
            route: ProfileSettingsRoute,
        ): ProfileSettingsComponent
    }

    private companion object {
        const val MODEL_KEY = "search-settings-model"
    }
}
