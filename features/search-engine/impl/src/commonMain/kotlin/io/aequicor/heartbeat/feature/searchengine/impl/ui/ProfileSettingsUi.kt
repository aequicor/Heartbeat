package io.aequicor.heartbeat.feature.searchengine.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedScope
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbGlassScene
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.searchengine.api.SearchConfiguration
import io.aequicor.heartbeat.feature.searchengine.api.SearchConnection
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchOperation
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.CheckPhase
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.SearchSettingsIntent
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.SearchSettingsModel
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.SearchSettingsState
import io.aequicor.heartbeat.feature.searchengine.impl.resources.Res
import io.aequicor.heartbeat.feature.searchengine.impl.resources.api_host
import io.aequicor.heartbeat.feature.searchengine.impl.resources.api_key
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_authentication
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_connection
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_connectivity
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_failed
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_invalid_input
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_invalid_response
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_not_configured
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_rate_limited
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_success
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_timeout
import io.aequicor.heartbeat.feature.searchengine.impl.resources.check_unavailable
import io.aequicor.heartbeat.feature.searchengine.impl.resources.contents_provider
import io.aequicor.heartbeat.feature.searchengine.impl.resources.key_configured
import io.aequicor.heartbeat.feature.searchengine.impl.resources.key_missing
import io.aequicor.heartbeat.feature.searchengine.impl.resources.prefer_native
import io.aequicor.heartbeat.feature.searchengine.impl.resources.provider_querit
import io.aequicor.heartbeat.feature.searchengine.impl.resources.remove_key
import io.aequicor.heartbeat.feature.searchengine.impl.resources.save_host
import io.aequicor.heartbeat.feature.searchengine.impl.resources.save_key
import io.aequicor.heartbeat.feature.searchengine.impl.resources.search_provider
import io.aequicor.heartbeat.feature.searchengine.impl.resources.settings_back
import io.aequicor.heartbeat.feature.searchengine.impl.resources.settings_error
import io.aequicor.heartbeat.feature.searchengine.impl.resources.settings_loading
import io.aequicor.heartbeat.feature.searchengine.impl.resources.settings_title
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/**
 * Profile route that owns only screen lifetime; the search service and credentials remain profile-owned.
 * The model lives in the retained [screen] scope: it survives configuration changes and its coroutines are
 * cancelled when the screen scope closes with the component.
 */
@AssistedInject
internal class ProfileSettingsComponent(
    @Assisted context: ComponentContext,
    @Assisted private val navigator: Navigator,
    @Assisted screen: ScopeHandle,
    configuration: SearchConfiguration,
    factory: HeartbeatStoreFactory,
) : ComponentContext by context,
    ComposableComponent {
    private val model = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(SearchSettingsModel(configuration, factory, screen.coroutineScope))
    }.model

    @Composable override fun Content(modifier: Modifier) = ProfileSettingsScreen(model, navigator::close, modifier)

    private class RetainedModel(val model: SearchSettingsModel) : InstanceKeeper.Instance

    @AssistedFactory
    fun interface Factory {
        fun create(context: ComponentContext, navigator: Navigator, screen: ScopeHandle): ProfileSettingsComponent
    }

    private companion object {
        const val MODEL_KEY = "search-settings-model"
    }
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ProfileSettingsRouteEntry(
    private val factory: ProfileSettingsComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<ProfileSettingsRoute>(ProfileSettingsRoute::class, ProfileSettingsRoute.serializer()) {
    override fun create(route: ProfileSettingsRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        factory.create(context, navigator, context.retainedScope(scopes, profile, name = "profile-settings"))
}

@Composable
private fun ProfileSettingsScreen(model: SearchSettingsModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by produceState(SearchSettingsState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    HbGlassScene(modifier.fillMaxSize().testTag("profile-settings")) {
        HbLazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
            item {
                HbColumn {
                    HbButton(stringResource(Res.string.settings_back), onBack, style = HbButtonStyle.Quiet)
                    HbText(stringResource(Res.string.settings_title), style = HbTheme.typography.display)
                }
            }
            val settings = state.settings
            if (settings == null) {
                item { HbText(stringResource(Res.string.settings_loading)) }
            } else {
                item {
                    ConnectionCard(
                        SearchOperation.Search,
                        settings.search,
                        state.searchHost,
                        state.searchCheck,
                        state.searchCheckFailure,
                        model.store::intent,
                    )
                }
                item {
                    ConnectionCard(
                        SearchOperation.Contents,
                        settings.contents,
                        state.contentsHost,
                        state.contentsCheck,
                        state.contentsCheckFailure,
                        model.store::intent,
                    )
                }
                item {
                    HbPanel(Modifier.fillMaxWidth()) {
                        HbSwitch(
                            settings.preferNative,
                            { model.store.intent(SearchSettingsIntent.PreferNative(it)) },
                            stringResource(Res.string.prefer_native),
                        )
                        HbText(stringResource(Res.string.prefer_native))
                    }
                }
            }
            if (state.failure != null) {
                item {
                    HbText(
                        stringResource(Res.string.settings_error),
                        color = HbTheme.colors.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    operation: SearchOperation,
    connection: SearchConnection,
    host: String,
    phase: CheckPhase,
    checkFailure: SearchFailure?,
    onIntent: (SearchSettingsIntent) -> Unit,
) {
    HbPanel(Modifier.fillMaxWidth().padding(HbTheme.spacing.m)) {
        HbColumn(Modifier.padding(HbTheme.spacing.l)) {
            HbText(
                stringResource(
                    if (operation == SearchOperation.Search) {
                        Res.string.search_provider
                    } else {
                        Res.string.contents_provider
                    },
                ),
                style = HbTheme.typography.title,
            )
            HbText(stringResource(Res.string.provider_querit))
            ConnectionKeyEditor(operation, connection.hasKey, onIntent)
            HbTextField(
                host,
                { onIntent(SearchSettingsIntent.EditHost(operation, it)) },
                Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.api_host),
            )
            HbButton(stringResource(Res.string.save_host), { onIntent(SearchSettingsIntent.SaveHost(operation)) })
            ConnectionCheck(operation, connection.hasKey, phase, checkFailure, onIntent)
        }
    }
}

@Composable
private fun ConnectionKeyEditor(operation: SearchOperation, hasKey: Boolean, onIntent: (SearchSettingsIntent) -> Unit) {
    var keyInput by remember(operation) { mutableStateOf("") }
    HbText(stringResource(if (hasKey) Res.string.key_configured else Res.string.key_missing))
    HbTextField(
        keyInput,
        { keyInput = it },
        Modifier.fillMaxWidth(),
        placeholder = stringResource(Res.string.api_key),
        isSecret = true,
    )
    HbButton(stringResource(Res.string.save_key), {
        onIntent(SearchSettingsIntent.SaveKey(operation, Secret(keyInput.toCharArray())))
        keyInput = ""
    }, enabled = keyInput.isNotBlank())
    if (hasKey) {
        HbButton(
            stringResource(Res.string.remove_key),
            { onIntent(SearchSettingsIntent.SaveKey(operation, null)) },
            style = HbButtonStyle.Quiet,
        )
    }
}

@Composable
private fun ConnectionCheck(
    operation: SearchOperation,
    hasKey: Boolean,
    phase: CheckPhase,
    failure: SearchFailure?,
    onIntent: (SearchSettingsIntent) -> Unit,
) {
    HbButton(
        stringResource(Res.string.check_connection),
        { onIntent(SearchSettingsIntent.Check(operation)) },
        enabled = hasKey && phase != CheckPhase.Checking,
    )
    when (phase) {
        CheckPhase.Success -> HbText(stringResource(Res.string.check_success), color = HbTheme.colors.success)

        CheckPhase.Failure -> {
            HbText(stringResource(Res.string.check_failed), color = HbTheme.colors.error)
            if (failure != null) HbText(checkFailureLabel(failure), color = HbTheme.colors.error)
        }

        CheckPhase.Idle, CheckPhase.Checking -> Unit
    }
}

@Composable
private fun checkFailureLabel(failure: SearchFailure): String = stringResource(
    when (failure) {
        SearchFailure.NotConfigured -> Res.string.check_not_configured
        SearchFailure.InvalidInput -> Res.string.check_invalid_input
        SearchFailure.Authentication -> Res.string.check_authentication
        SearchFailure.RateLimited -> Res.string.check_rate_limited
        SearchFailure.Connectivity -> Res.string.check_connectivity
        SearchFailure.Timeout -> Res.string.check_timeout
        SearchFailure.InvalidResponse -> Res.string.check_invalid_response
        SearchFailure.Unavailable -> Res.string.check_unavailable
    },
)
