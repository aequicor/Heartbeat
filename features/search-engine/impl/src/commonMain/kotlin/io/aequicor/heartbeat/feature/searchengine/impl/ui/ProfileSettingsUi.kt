package io.aequicor.heartbeat.feature.searchengine.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import io.aequicor.heartbeat.core.secrets.SecretStorageInfo
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
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
import io.aequicor.heartbeat.feature.searchengine.impl.resources.api_host_label
import io.aequicor.heartbeat.feature.searchengine.impl.resources.api_key
import io.aequicor.heartbeat.feature.searchengine.impl.resources.api_key_label
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
import io.aequicor.heartbeat.feature.searchengine.impl.resources.engine_section
import io.aequicor.heartbeat.feature.searchengine.impl.resources.key_configured
import io.aequicor.heartbeat.feature.searchengine.impl.resources.key_missing
import io.aequicor.heartbeat.feature.searchengine.impl.resources.key_storage_development
import io.aequicor.heartbeat.feature.searchengine.impl.resources.key_storage_protected
import io.aequicor.heartbeat.feature.searchengine.impl.resources.prefer_native
import io.aequicor.heartbeat.feature.searchengine.impl.resources.prefer_native_hint
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
    storage: SecretStorageInfo,
) : ComponentContext by context,
    ComposableComponent {
    private val model = instanceKeeper.getOrCreate(MODEL_KEY) {
        RetainedModel(SearchSettingsModel(configuration, factory, screen.coroutineScope, storage.protection))
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
private fun ProfileSettingsScreen(model: SearchSettingsModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(SearchSettingsState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    ProfileSettingsContent(state, model.store::intent, onBack, modifier)
}

/**
 * Search providers of the profile as settings sections: one per operation (key, API URL, connection check) and
 * the engine preference. Keys are typed into a secret field, sent once and cleared; they are never shown back.
 * Inside the settings host [onBack] is null and only this content is drawn.
 */
@Composable
internal fun ProfileSettingsContent(
    state: SearchSettingsState,
    onIntent: (SearchSettingsIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("profile-settings"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.settings_title),
                leadingInset = HbTheme.dimensions.titlebarLeadingInset,
                navigation = {
                    HbIconButton(
                        HbIcons.ArrowLeft,
                        stringResource(Res.string.settings_back),
                        onBack,
                        Modifier.testTag("profile-settings-back"),
                    )
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxWidth()
                    .hbVerticalScroll(rememberScrollState())
                    .padding(HbTheme.spacing.xl),
                gap = HbTheme.spacing.xxl,
            ) {
                SettingsBody(state, onIntent)
            }
        }
    }
}

@Composable
private fun SettingsBody(state: SearchSettingsState, onIntent: (SearchSettingsIntent) -> Unit) {
    HbText(
        stringResource(
            if (state.isKeyStorageProtected) Res.string.key_storage_protected else Res.string.key_storage_development,
        ),
        style = HbTheme.typography.caption,
        color = HbTheme.colors.textSecondary,
    )
    if (state.failure != null) HbBanner(stringResource(Res.string.settings_error))
    val settings = state.settings
    if (settings == null) {
        HbLoadingState(stringResource(Res.string.settings_loading))
        return
    }
    HbSettingsSection(stringResource(Res.string.engine_section)) {
        val label = stringResource(Res.string.prefer_native)
        HbSettingsRow(label, description = stringResource(Res.string.prefer_native_hint)) {
            HbSwitch(
                settings.preferNative,
                { onIntent(SearchSettingsIntent.PreferNative(it)) },
                label,
                Modifier.testTag("prefer-native"),
            )
        }
    }
    ConnectionSection(
        SearchOperation.Search,
        settings.search,
        state.searchHost,
        state.searchCheck,
        state.searchCheckFailure,
        onIntent,
    )
    ConnectionSection(
        SearchOperation.Contents,
        settings.contents,
        state.contentsHost,
        state.contentsCheck,
        state.contentsCheckFailure,
        onIntent,
    )
}

@Composable
private fun ConnectionSection(
    operation: SearchOperation,
    connection: SearchConnection,
    host: String,
    phase: CheckPhase,
    checkFailure: SearchFailure?,
    onIntent: (SearchSettingsIntent) -> Unit,
) {
    val title = if (operation == SearchOperation.Search) Res.string.search_provider else Res.string.contents_provider
    HbSettingsSection(stringResource(title), description = stringResource(Res.string.provider_querit)) {
        ConnectionKeyEditor(operation, connection.hasKey, onIntent)
        HbDivider()
        HbSettingsRow(stringResource(Res.string.api_host_label)) {
            HbTextField(
                host,
                { onIntent(SearchSettingsIntent.EditHost(operation, it)) },
                Modifier.widthIn(min = HbTheme.dimensions.composerMenuMinWidth).testTag("host:$operation"),
                placeholder = stringResource(Res.string.api_host),
                accessibleLabel = stringResource(Res.string.api_host_label),
            )
            HbButton(
                stringResource(Res.string.save_host),
                { onIntent(SearchSettingsIntent.SaveHost(operation)) },
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
        HbDivider()
        ConnectionCheck(operation, connection.hasKey, phase, checkFailure, onIntent)
    }
}

@Composable
private fun ConnectionKeyEditor(operation: SearchOperation, hasKey: Boolean, onIntent: (SearchSettingsIntent) -> Unit) {
    var keyInput by remember(operation) { mutableStateOf("") }
    HbSettingsRow(
        stringResource(Res.string.api_key_label),
        description = stringResource(if (hasKey) Res.string.key_configured else Res.string.key_missing),
    ) {
        if (hasKey) {
            HbButton(
                stringResource(Res.string.remove_key),
                { onIntent(SearchSettingsIntent.SaveKey(operation, null)) },
                Modifier.testTag("remove-key:$operation"),
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
    }
    HbRow(
        Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs),
        gap = HbTheme.spacing.s,
    ) {
        HbTextField(
            keyInput,
            { keyInput = it },
            Modifier.weight(1f).testTag("key:$operation"),
            placeholder = stringResource(Res.string.api_key),
            accessibleLabel = stringResource(Res.string.api_key_label),
            isSecret = true,
        )
        HbButton(
            stringResource(Res.string.save_key),
            {
                onIntent(SearchSettingsIntent.SaveKey(operation, Secret(keyInput.toCharArray())))
                keyInput = ""
            },
            Modifier.testTag("save-key:$operation"),
            style = HbButtonStyle.Secondary,
            enabled = keyInput.isNotBlank(),
            size = HbButtonSize.Small,
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
    val status = when (phase) {
        CheckPhase.Success -> stringResource(Res.string.check_success)

        CheckPhase.Failure -> listOfNotNull(
            stringResource(Res.string.check_failed),
            failure?.let { checkFailureLabel(it) },
        ).joinToString(" · ")

        CheckPhase.Idle, CheckPhase.Checking -> null
    }
    HbSettingsRow(stringResource(Res.string.check_connection), description = status) {
        if (phase == CheckPhase.Checking) HbActivityIndicator()
        HbButton(
            stringResource(Res.string.check_connection),
            { onIntent(SearchSettingsIntent.Check(operation)) },
            Modifier.testTag("check:$operation"),
            style = HbButtonStyle.Secondary,
            enabled = hasKey && phase != CheckPhase.Checking,
            size = HbButtonSize.Small,
        )
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
