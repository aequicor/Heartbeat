package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSecurityLevelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSecurityModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSecurityScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSecurityScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_ask
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_ask_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_automatic
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_automatic_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_back
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_level
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_load_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_loading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_retry
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_save_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_saving
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_scope
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_trust
import io.aequicor.heartbeat.feature.aistudio.impl.resources.security.security_trust_hint
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Confirmation policy of the active profile. */
@Composable
internal fun StudioSecurityScreen(model: StudioSecurityModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(StudioSecurityScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    StudioSecurityContent(state, model.store::intent, onBack, modifier)
}

/** Three explicit levels with their scope, durable save status and recovery from unreadable settings. */
@Composable
internal fun StudioSecurityContent(
    state: StudioSecurityScreenState,
    onIntent: (StudioSecurityScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("studio-security"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) SecurityHeader(onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                item(key = "levels") { SecurityLevels(state, onIntent) }
                item(key = "status") { SecurityStatus(state, onIntent) }
            }
        }
    }
}

@Composable
private fun SecurityHeader(onBack: () -> Unit) {
    HbPaneHeader(
        stringResource(Res.string.security_title),
        leadingInset = if (HbTheme.dimensions.isDesktop) HbTheme.spacing.m else HbTheme.dimensions.titlebarLeadingInset,
        navigation = {
            HbIconButton(HbIcons.ArrowLeft, stringResource(Res.string.security_back), onBack)
        },
    )
}

@Composable
private fun SecurityLevels(state: StudioSecurityScreenState, onIntent: (StudioSecurityScreenIntent) -> Unit) {
    HbSettingsSection(
        stringResource(Res.string.security_level),
        description = stringResource(Res.string.security_scope),
    ) {
        HbColumn(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.l) {
            StudioSecurityLevelUi.entries.forEach { level ->
                HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
                    HbChoiceRow(
                        stringResource(level.title()),
                        selected = state.level == level,
                        isMultiple = false,
                        onClick = { onIntent(StudioSecurityScreenIntent.Select(level)) },
                        modifier = Modifier.testTag("studio-security-${level.name}"),
                        enabled = state.isLoaded && !state.isSaving,
                    )
                    HbText(
                        stringResource(level.description()),
                        Modifier.padding(horizontal = HbTheme.spacing.s),
                        color = HbTheme.colors.textSecondary,
                        style = HbTheme.typography.caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityStatus(state: StudioSecurityScreenState, onIntent: (StudioSecurityScreenIntent) -> Unit) {
    when {
        state.isLoadFailed -> HbBanner(
            stringResource(Res.string.security_load_failed),
            Modifier.testTag("studio-security-load-error"),
        ) {
            HbButton(
                stringResource(Res.string.security_retry),
                { onIntent(StudioSecurityScreenIntent.Retry) },
                Modifier.testTag("studio-security-retry"),
                style = HbButtonStyle.Ghost,
            )
        }

        state.isSaveFailed -> HbBanner(
            stringResource(Res.string.security_save_failed),
            Modifier.testTag("studio-security-save-error"),
        )

        state.isSaving -> HbLoadingState(stringResource(Res.string.security_saving))

        !state.isLoaded -> HbLoadingState(stringResource(Res.string.security_loading))
    }
}

private fun StudioSecurityLevelUi.title() = when (this) {
    StudioSecurityLevelUi.Ask -> Res.string.security_ask
    StudioSecurityLevelUi.Automatic -> Res.string.security_automatic
    StudioSecurityLevelUi.Trust -> Res.string.security_trust
}

private fun StudioSecurityLevelUi.description() = when (this) {
    StudioSecurityLevelUi.Ask -> Res.string.security_ask_hint
    StudioSecurityLevelUi.Automatic -> Res.string.security_automatic_hint
    StudioSecurityLevelUi.Trust -> Res.string.security_trust_hint
}

@Preview
@Composable
private fun StudioSecurityLightPreview() {
    HbTheme(darkTheme = false) { StudioSecurityContent(StudioSecurityScreenState(isLoaded = true), {}, null) }
}

@Preview
@Composable
private fun StudioSecurityDarkPreview() {
    HbTheme(darkTheme = true) { StudioSecurityContent(StudioSecurityScreenState(isLoaded = true), {}, null) }
}
