package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbGlassScene
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbAdaptivePane
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
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
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_models_selected
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_cli_hint
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_connect
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_connecting
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_engine_intro
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_engine_not_connectable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_engines_empty
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

@Composable
internal fun ConnectWizardContent(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbGlassScene(modifier.fillMaxSize().testTag("connect-wizard")) {
        HbColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(HbTheme.spacing.xl)) {
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
    HbColumn(modifier, gap = HbTheme.spacing.xs) {
        HbText(stringResource(Res.string.wizard_title), style = HbTheme.typography.display)
        HbText(
            stringResource(Res.string.wizard_step, number, stringResource(step)) +
                state.engineTitle.takeIf { state.step != WizardStep.Engine && it.isNotEmpty() }?.let { " · $it" }
                    .orEmpty(),
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
    HbLazyColumn(modifier.fillMaxSize().testTag("wizard-engines"), gap = HbTheme.spacing.m) {
        item(key = "intro") { HbText(stringResource(Res.string.wizard_engine_intro)) }
        state.failure?.let { failure ->
            item(key = "failure") { FailurePanel(failure, onRetry = { onIntent(ConnectWizardScreenIntent.Retry) }) }
        }
        val engines = state.engines
        when {
            engines == null && state.failure == null -> item(key = "loading") {
                HbText(stringResource(Res.string.conn_loading), color = HbTheme.colors.textSecondary)
            }

            engines != null && engines.isEmpty() -> item(key = "empty") {
                HbText(stringResource(Res.string.wizard_engines_empty), color = HbTheme.colors.textSecondary)
            }

            else -> items(engines.orEmpty(), key = { it.id }) { engine ->
                EngineCard(engine, onClick = { onIntent(ConnectWizardScreenIntent.ChooseEngine(engine.id)) })
            }
        }
    }
}

@Composable
private fun EngineCard(engine: EngineRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SelectableRow(
        isSelected = false,
        onClick = onClick,
        modifier = modifier.testTag("engine:${engine.id}"),
        enabled = engine.isConnectable,
    ) {
        HbRow(gap = HbTheme.spacing.m) {
            HbText(engine.title, style = HbTheme.typography.title)
            AvailabilityBadge(engine.availability)
        }
        HbText(
            if (engine.isConnectable) {
                stringResource(Res.string.conn_connections_count, engine.connections)
            } else {
                stringResource(Res.string.wizard_engine_not_connectable)
            },
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun MethodStep(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbAdaptivePane(
        sidebar = { MethodList(state, onIntent) },
        modifier = modifier.fillMaxSize(),
        // On narrow screens the provider list must leave room for the credential form below it.
        compactSidebar = {
            MethodList(state, onIntent, Modifier.heightIn(max = HbTheme.dimensions.composerMenuMaxHeight))
        },
    ) {
        val method = state.methods.firstOrNull { it.id == state.selectedMethod }
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
    HbColumn(modifier, gap = HbTheme.spacing.s) {
        HbTextField(
            state.methodQuery,
            { onIntent(ConnectWizardScreenIntent.SearchMethods(it)) },
            Modifier.fillMaxWidth().testTag("wizard-method-search"),
            placeholder = stringResource(Res.string.wizard_method_search),
        )
        if (visible.isEmpty()) {
            HbText(stringResource(Res.string.wizard_methods_empty), color = HbTheme.colors.textSecondary)
        }
        HbLazyColumn(
            Modifier.weight(1f, fill = false).fillMaxWidth(),
            gap = HbTheme.spacing.xs,
            contentPadding = PaddingValues(HbTheme.spacing.none),
        ) {
            items(visible, key = { it.id }) { method ->
                SelectableRow(
                    isSelected = method.id == state.selectedMethod,
                    onClick = { onIntent(ConnectWizardScreenIntent.SelectMethod(method.id)) },
                    modifier = Modifier.testTag("method:${method.id}"),
                    enabled = !state.isBusy,
                ) {
                    HbText(method.provider, style = HbTheme.typography.label)
                    HbText(
                        methodKindLabel(method.kind),
                        style = HbTheme.typography.caption,
                        color = HbTheme.colors.textSecondary,
                    )
                }
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
    HbColumn(
        modifier.fillMaxSize()
            .hbVerticalScroll(rememberScrollState())
            .widthIn(max = HbTheme.dimensions.chatMessageMaxWidth),
        gap = HbTheme.spacing.m,
    ) {
        HbRow(gap = HbTheme.spacing.m) {
            HbText(method.provider, style = HbTheme.typography.title)
            HbText(methodKindLabel(method.kind), color = HbTheme.colors.textSecondary)
        }
        when (method.kind) {
            MethodKindUi.ApiKey -> {
                HbText(stringResource(Res.string.wizard_field_key), style = HbTheme.typography.label)
                HbTextField(
                    state.form.key.value,
                    { onIntent(ConnectWizardScreenIntent.EditKey(it)) },
                    Modifier.fillMaxWidth().testTag("wizard-key"),
                    placeholder = stringResource(Res.string.wizard_key_placeholder),
                    enabled = isEnabled,
                    accessibleLabel = stringResource(Res.string.wizard_field_key),
                    isSecret = true,
                )
                HbText(
                    stringResource(
                        if (state.isKeyStorageProtected) {
                            Res.string.wizard_key_hint
                        } else {
                            Res.string.wizard_key_hint_development
                        },
                    ),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
                method.credentialsPage?.let { page ->
                    HbButton(
                        stringResource(Res.string.wizard_get_key),
                        { openCredentialsPage(uriHandler, page) },
                        style = HbButtonStyle.Quiet,
                    )
                }
            }

            MethodKindUi.CliLogin -> HbText(stringResource(Res.string.wizard_cli_hint))

            MethodKindUi.NoAuth -> HbText(stringResource(Res.string.wizard_no_auth_hint))
        }
        HbText(stringResource(Res.string.wizard_field_host), style = HbTheme.typography.label)
        HbTextField(
            if (method.isOriginEditable) state.form.origin else method.origin,
            { onIntent(ConnectWizardScreenIntent.EditOrigin(it)) },
            Modifier.fillMaxWidth().testTag("wizard-host"),
            enabled = isEnabled && method.isOriginEditable,
            accessibleLabel = stringResource(Res.string.wizard_field_host),
        )
        if (!method.isOriginEditable) {
            HbText(
                stringResource(Res.string.wizard_host_fixed),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        HbText(stringResource(Res.string.wizard_field_label), style = HbTheme.typography.label)
        HbTextField(
            state.form.label,
            { onIntent(ConnectWizardScreenIntent.EditLabel(it)) },
            Modifier.fillMaxWidth().testTag("wizard-label"),
            enabled = isEnabled,
            accessibleLabel = stringResource(Res.string.wizard_field_label),
        )
        state.formError?.let { error ->
            HbBadge(
                stringResource(
                    when (error) {
                        FormError.MissingKey -> Res.string.wizard_error_missing_key
                        FormError.InvalidOrigin -> Res.string.wizard_error_invalid_host
                    },
                ),
                Modifier.testTag("wizard-form-error"),
                HbTone.Danger,
            )
        }
        state.failure?.let { FailurePanel(it) }
    }
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
        HbText(stringResource(Res.string.wizard_models_intro))
        state.failure?.let { failure ->
            FailurePanel(failure, onRetry = { onIntent(ConnectWizardScreenIntent.Retry) }) {
                HbButton(
                    stringResource(Res.string.wizard_finish_without_models),
                    { onIntent(ConnectWizardScreenIntent.Finish) },
                    style = HbButtonStyle.Quiet,
                )
            }
        }
        when {
            models == null && state.failure == null ->
                HbText(stringResource(Res.string.conn_models_discovering), color = HbTheme.colors.textSecondary)

            models != null && models.isEmpty() ->
                HbText(stringResource(Res.string.conn_models_empty), color = HbTheme.colors.textSecondary)

            models != null -> {
                ModelToolbar(state.modelQuery, models.count { it.isEnabled }, !state.isBusy, onIntent)
                HbLazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("wizard-models"),
                    gap = HbTheme.spacing.xs,
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
    HbFlowRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbTextField(
            query,
            { onIntent(ConnectWizardScreenIntent.SearchModels(it)) },
            Modifier.widthIn(min = HbTheme.dimensions.composerMenuMinWidth).testTag("wizard-model-search"),
            placeholder = stringResource(Res.string.conn_models_search),
        )
        HbButton(
            stringResource(Res.string.conn_models_all),
            { onIntent(ConnectWizardScreenIntent.SelectAllModels(true)) },
            style = HbButtonStyle.Secondary,
            enabled = enabled,
        )
        HbButton(
            stringResource(Res.string.conn_models_none),
            { onIntent(ConnectWizardScreenIntent.SelectAllModels(false)) },
            style = HbButtonStyle.Quiet,
            enabled = enabled,
        )
        HbText(stringResource(Res.string.conn_models_selected, selected), color = HbTheme.colors.textSecondary)
    }
}

/** Model with an availability switch; shared by the wizard and the settings space. */
@Composable
internal fun ModelRow(
    model: ModelRowUi,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    HbRow(modifier.fillMaxWidth().testTag("model:${model.id}"), gap = HbTheme.spacing.m) {
        HbSwitch(
            model.isEnabled,
            onEnabledChange,
            model.title,
            Modifier.testTag("model-switch:${model.id}"),
            enabled,
        )
        HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.xxs) {
            HbText(model.title)
            HbText(
                listOfNotNull(
                    model.id.takeIf { it != model.title },
                    model.contextLimit?.let { stringResource(Res.string.conn_context_limit, it.toString()) },
                ).joinToString(" · "),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        trailing()
    }
}

@Composable
private fun WizardFooter(
    state: ConnectWizardScreenState,
    onIntent: (ConnectWizardScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbFlowRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbButton(
            stringResource(Res.string.conn_cancel),
            { onIntent(ConnectWizardScreenIntent.Cancel) },
            Modifier.testTag("wizard-cancel"),
            style = HbButtonStyle.Quiet,
            enabled = state.isCancelAllowed,
        )
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
