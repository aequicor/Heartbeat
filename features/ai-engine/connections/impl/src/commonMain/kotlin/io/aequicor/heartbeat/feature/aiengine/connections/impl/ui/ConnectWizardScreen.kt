package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSearchField
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardModel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenAction
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.FormError
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.MethodKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.MethodRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.WizardStep
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.matches
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_back
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_cancel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_connections_count
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_context_limit
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_loading
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_all
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_discovering
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_empty
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_none
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_search
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_search_clear
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_selected
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_base_url_hint
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_cli_hint
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_connect
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_connecting
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_engine_intro
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_engine_not_connectable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_engines_empty
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_error_insecure_host
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_error_invalid_host
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_error_missing_key
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_field_host
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_field_key
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_field_label
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_finish
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_finish_without_models
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_get_key
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_host_fixed
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_key_hint
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_key_hint_development
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_key_placeholder
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_method_search
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_methods_empty
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_models_intro
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_no_auth_hint
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_step
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_step_engine
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_step_method
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_step_models
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

private val log = Log.tag("ConnectWizardScreen")

/** Opens the provider page; a device without a browser must not crash the wizard. */
private fun openCredentialsPage(uriHandler: UriHandler, page: String) {
    log.i { "open provider credentials page" }
    try {
        uriHandler.openUri(page)
    } catch (e: IllegalStateException) {
        log.w(e) { "no application can open the provider page" }
    } catch (e: IllegalArgumentException) {
        log.w(e) { "provider page rejected by the platform" }
    }
}

@Composable
internal fun ConnectWizardScreen(
    model: ConnectWizardModel,
    onClose: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val close by rememberUpdatedState(onClose)
    val state by produceState(ConnectWizardScreenState(), model) {
        model.store.collect {
            launch {
                actions.collect { action ->
                    when (action) {
                        is ConnectWizardScreenAction.Close -> close(action.binding)
                    }
                }
            }
            states.collect { value = it }
        }
    }
    ConnectWizardContent(state, model.store::intent, modifier)
}

/**
 * The "engine → authentication → models" steps as content of the settings section: a heading with the step,
 * the step's settings rows and a footer with actions aligned to the trailing edge. The settings host draws the
 * window chrome; the wizard never opens over the whole window.
 */
@Composable
internal fun ConnectWizardContent(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        // Wait until the focus target is attached and laid out.
        withFrameNanos { }
        focus.requestFocus()
    }
    Box(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("connect-wizard")
            // Esc is the wizard's own back: a step back or a rollback of a created connection, never a plain pop.
            .onKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                    onIntent(ConnectWizardScreenIntent.SystemBack)
                    true
                } else {
                    false
                }
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.TopCenter,
    ) {
        HbColumn(
            Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize().padding(HbTheme.spacing.xl),
            gap = HbTheme.spacing.l,
        ) {
            WizardHeader(state)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.step) {
                    WizardStep.Engine -> EngineStep(state, onIntent)
                    WizardStep.Method -> MethodStep(state, onIntent)
                    WizardStep.Models, WizardStep.Done -> ModelsStep(state, onIntent)
                }
            }
            WizardFooter(state, onIntent)
        }
    }
}

@Composable
private fun WizardHeader(state: ConnectWizardScreenState, modifier: Modifier = Modifier) {
    val (number, step) = when (state.step) {
        WizardStep.Engine -> 1 to Res.string.wizard_step_engine
        WizardStep.Method -> 2 to Res.string.wizard_step_method
        WizardStep.Models, WizardStep.Done -> 3 to Res.string.wizard_step_models
    }
    HbColumn(modifier, gap = HbTheme.spacing.xxs) {
        HbText(
            stringResource(Res.string.wizard_title),
            Modifier.semantics { heading() },
            style = HbTheme.typography.title.copy(fontWeight = FontWeight.SemiBold),
        )
        HbText(
            stringResource(Res.string.wizard_step, number, stringResource(step)) +
                state.engineTitle.takeIf { state.step != WizardStep.Engine && it.isNotEmpty() }?.let { " · $it" }
                    .orEmpty(),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun EngineStep(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbLazyColumn(
        modifier.fillMaxSize().testTag("wizard-engines"),
        gap = HbTheme.spacing.none,
        contentPadding = PaddingValues(HbTheme.spacing.none),
    ) {
        item(key = "intro") {
            HbText(
                stringResource(Res.string.wizard_engine_intro),
                Modifier.padding(bottom = HbTheme.spacing.s),
                color = HbTheme.colors.textSecondary,
            )
        }
        state.failure?.let { failure ->
            item(key = "failure") { FailurePanel(failure, onRetry = { onIntent(ConnectWizardScreenIntent.Retry) }) }
        }
        val engines = state.engines
        when {
            engines == null && state.failure == null -> item(key = "loading") {
                HbLoadingState(stringResource(Res.string.conn_loading))
            }

            engines != null && engines.isEmpty() -> item(key = "empty") {
                HbEmptyState(stringResource(Res.string.wizard_engines_empty))
            }

            else -> itemsIndexed(engines.orEmpty(), key = { _, engine -> engine.id }) { index, engine ->
                if (index > 0) HbDivider()
                EngineCard(engine, onClick = { onIntent(ConnectWizardScreenIntent.ChooseEngine(engine.id)) })
            }
        }
    }
}

@Composable
private fun EngineCard(engine: EngineRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SelectableRow(
        engine.title,
        isSelected = false,
        onClick = onClick,
        modifier = modifier.testTag("engine:${engine.id}"),
        description = if (engine.isConnectable) {
            stringResource(Res.string.conn_connections_count, engine.connections)
        } else {
            stringResource(Res.string.wizard_engine_not_connectable)
        },
        enabled = engine.isConnectable,
    ) {
        AvailabilityBadge(engine.availability)
        HbIcon(HbIcons.ChevronRight, null, tint = HbTheme.colors.textSecondary)
    }
}

@Composable
private fun MethodStep(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val method = state.methods.firstOrNull { it.id == state.selectedMethod }
    HbColumn(modifier.fillMaxSize().hbVerticalScroll(rememberScrollState()), gap = HbTheme.spacing.xl) {
        MethodList(state, onIntent)
        if (method != null) MethodForm(state, method, onIntent)
    }
}

@Composable
private fun MethodList(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(state.methods, state.methodQuery) {
        state.methods.filter { matches(state.methodQuery, it.provider, it.origin) }
    }
    HbSettingsSection(stringResource(Res.string.wizard_step_method), modifier) {
        HbSearchField(
            state.methodQuery,
            { onIntent(ConnectWizardScreenIntent.SearchMethods(it)) },
            placeholder = stringResource(Res.string.wizard_method_search),
            clearLabel = stringResource(Res.string.conn_models_search_clear),
            modifier = Modifier.fillMaxWidth().padding(bottom = HbTheme.spacing.s).testTag("wizard-method-search"),
        )
        if (visible.isEmpty()) HbEmptyState(stringResource(Res.string.wizard_methods_empty))
        HbColumn(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.none) {
            visible.forEachIndexed { index, method ->
                if (index > 0) HbDivider()
                SelectableRow(
                    method.provider,
                    isSelected = method.id == state.selectedMethod,
                    onClick = { onIntent(ConnectWizardScreenIntent.SelectMethod(method.id)) },
                    modifier = Modifier.testTag("method:${method.id}"),
                    description = methodKindLabel(method.kind),
                    enabled = !state.isBusy,
                )
            }
        }
    }
}

@Composable
private fun MethodForm(
    state: ConnectWizardScreenState,
    method: MethodRowUi,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val isEnabled = !state.isBusy
    HbSettingsSection(method.provider, modifier, description = methodKindLabel(method.kind)) {
        HbColumn(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.s) {
            when (method.kind) {
                MethodKindUi.ApiKey -> KeyField(
                    state,
                    method,
                    isEnabled,
                    onIntent,
                ) { openCredentialsPage(uriHandler, it) }

                MethodKindUi.CliLogin -> Hint(stringResource(Res.string.wizard_cli_hint))

                MethodKindUi.NoAuth -> Hint(stringResource(Res.string.wizard_no_auth_hint))
            }
            HostField(state, method, isEnabled, onIntent)
            FieldLabel(stringResource(Res.string.wizard_field_label))
            HbTextField(
                state.form.label,
                { onIntent(ConnectWizardScreenIntent.EditLabel(it)) },
                Modifier.fillMaxWidth().testTag("wizard-label"),
                enabled = isEnabled,
                accessibleLabel = stringResource(Res.string.wizard_field_label),
            )
            state.formError?.let { error ->
                HbBanner(
                    stringResource(
                        when (error) {
                            FormError.MissingKey -> Res.string.wizard_error_missing_key
                            FormError.InvalidOrigin -> Res.string.wizard_error_invalid_host
                            FormError.InsecureOrigin -> Res.string.wizard_error_insecure_host
                        },
                    ),
                    Modifier.testTag("wizard-form-error"),
                )
            }
            state.failure?.let { FailurePanel(it) }
        }
    }
}

/** The secret key field; its value is never shown back, logged or saved in UI state. */
@Composable
private fun KeyField(
    state: ConnectWizardScreenState,
    method: MethodRowUi,
    isEnabled: Boolean,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    onOpenPage: (String) -> Unit,
) {
    FieldLabel(stringResource(Res.string.wizard_field_key))
    HbTextField(
        state.form.key.value,
        { onIntent(ConnectWizardScreenIntent.EditKey(it)) },
        Modifier.fillMaxWidth().testTag("wizard-key"),
        placeholder = stringResource(Res.string.wizard_key_placeholder),
        enabled = isEnabled,
        accessibleLabel = stringResource(Res.string.wizard_field_key),
        isSecret = true,
    )
    Hint(
        stringResource(
            if (state.isKeyStorageProtected) Res.string.wizard_key_hint else Res.string.wizard_key_hint_development,
        ),
    )
    method.credentialsPage?.let { page ->
        HbButton(
            stringResource(Res.string.wizard_get_key),
            { onOpenPage(page) },
            style = HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    HbText(text, modifier.padding(top = HbTheme.spacing.xs), style = HbTheme.typography.label)
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    HbText(text, modifier, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
}

@Composable
private fun ModelsStep(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val models = state.models
    val visible = remember(models, state.modelQuery) {
        models.orEmpty().filter { matches(state.modelQuery, it.title, it.id) }
    }
    HbColumn(modifier.fillMaxSize(), gap = HbTheme.spacing.s) {
        Hint(stringResource(Res.string.wizard_models_intro))
        state.failure?.let { failure ->
            FailurePanel(failure, onRetry = { onIntent(ConnectWizardScreenIntent.Retry) }) {
                HbButton(
                    stringResource(Res.string.wizard_finish_without_models),
                    { onIntent(ConnectWizardScreenIntent.Finish) },
                    style = HbButtonStyle.Ghost,
                    size = HbButtonSize.Small,
                )
            }
        }
        when {
            models == null && state.failure == null -> HbLoadingState(
                stringResource(Res.string.conn_models_discovering),
            )

            models != null && models.isEmpty() -> HbEmptyState(stringResource(Res.string.conn_models_empty))

            models != null -> {
                ModelToolbar(state.modelQuery, models.count { it.isEnabled }, !state.isBusy, onIntent)
                HbLazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("wizard-models"),
                    gap = HbTheme.spacing.none,
                    contentPadding = PaddingValues(HbTheme.spacing.none),
                ) {
                    items(visible, key = { it.id }) { model ->
                        ModelRow(
                            model,
                            enabled = !state.isBusy,
                            onEnabledChange = { onIntent(ConnectWizardScreenIntent.ToggleModel(model.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelToolbar(
    query: String,
    selected: Int,
    enabled: Boolean,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbSearchField(
            query,
            { onIntent(ConnectWizardScreenIntent.SearchModels(it)) },
            placeholder = stringResource(Res.string.conn_models_search),
            clearLabel = stringResource(Res.string.conn_models_search_clear),
            modifier = Modifier.weight(1f).testTag("wizard-model-search"),
        )
        HbText(
            stringResource(Res.string.conn_models_selected, selected),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbButton(
            stringResource(Res.string.conn_models_all),
            { onIntent(ConnectWizardScreenIntent.SelectAllModels(true)) },
            style = HbButtonStyle.Ghost,
            enabled = enabled,
            size = HbButtonSize.Small,
        )
        HbButton(
            stringResource(Res.string.conn_models_none),
            { onIntent(ConnectWizardScreenIntent.SelectAllModels(false)) },
            style = HbButtonStyle.Ghost,
            enabled = enabled,
            size = HbButtonSize.Small,
        )
    }
}

/** Model as a settings row with an availability switch; shared by the wizard and the settings space. */
@Composable
internal fun ModelRow(
    model: ModelRowUi,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val details = listOfNotNull(
        model.id.takeIf { it != model.title },
        model.contextLimit?.let { stringResource(Res.string.conn_context_limit, it.toString()) },
    ).joinToString(" · ")
    HbSettingsRow(
        model.title,
        modifier.testTag("model:${model.id}"),
        description = details.ifEmpty { null },
    ) {
        trailing()
        HbSwitch(model.isEnabled, onEnabledChange, model.title, Modifier.testTag("model-switch:${model.id}"), enabled)
    }
}

@Composable
private fun WizardFooter(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbButton(
            stringResource(Res.string.conn_cancel),
            { onIntent(ConnectWizardScreenIntent.Cancel) },
            Modifier.testTag("wizard-cancel"),
            style = HbButtonStyle.Ghost,
            enabled = state.isCancelAllowed,
        )
        Box(Modifier.weight(1f))
        if (state.step == WizardStep.Method) {
            HbButton(
                stringResource(Res.string.conn_back),
                { onIntent(ConnectWizardScreenIntent.Back) },
                Modifier.testTag("wizard-back"),
                style = HbButtonStyle.Secondary,
                enabled = !state.isBusy,
            )
            HbButton(
                stringResource(if (state.isBusy) Res.string.wizard_connecting else Res.string.wizard_connect),
                { onIntent(ConnectWizardScreenIntent.Connect) },
                Modifier.testTag("wizard-connect"),
                enabled = !state.isBusy && state.selectedMethod != null,
            )
        }
        if (state.step == WizardStep.Models) {
            HbButton(
                stringResource(Res.string.wizard_finish),
                { onIntent(ConnectWizardScreenIntent.Finish) },
                Modifier.testTag("wizard-finish"),
                enabled = !state.isBusy && state.models != null,
            )
        }
    }
}

@Composable
private fun HostField(
    state: ConnectWizardScreenState,
    method: MethodRowUi,
    isEnabled: Boolean,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
) {
    FieldLabel(stringResource(Res.string.wizard_field_host))
    HbTextField(
        if (method.isOriginEditable) state.form.origin else method.origin,
        { onIntent(ConnectWizardScreenIntent.EditOrigin(it)) },
        Modifier.fillMaxWidth().testTag("wizard-host"),
        enabled = isEnabled && method.isOriginEditable,
        accessibleLabel = stringResource(Res.string.wizard_field_host),
    )
    if (method.isPathEditable) Hint(stringResource(Res.string.wizard_base_url_hint))
    if (!method.isOriginEditable) Hint(stringResource(Res.string.wizard_host_fixed))
}
