package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.harness.impl.resources.Res
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_back
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_load_failed
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_loading
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_not_found
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_retry
import org.jetbrains.compose.resources.stringResource

/**
 * Settings-width scrolling pane shared by the harness screens. The header with a back button is shown when
 * [onBack] is set (pushed screens and the standalone library); an embedded section shows only its content.
 */
@Composable
internal fun HarnessPane(
    title: String,
    tag: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag(tag),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                title,
                leadingInset = if (HbTheme.dimensions.isDesktop) {
                    HbTheme.spacing.m
                } else {
                    HbTheme.dimensions.titlebarLeadingInset
                },
                navigation = {
                    HbIconButton(
                        HbIcons.ArrowLeft,
                        stringResource(Res.string.harness_back),
                        onBack,
                        Modifier.testTag("$tag-back"),
                    )
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize().testTag("$tag-list"),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
                content = content,
            )
        }
    }
}

/**
 * Loading, failure and absence states shared by the screens; returns true when the content can be shown.
 * [onRetry] is offered for a failed initial load.
 */
internal fun LazyListScope.phaseItems(phase: PhaseUi, onRetry: (() -> Unit)? = null): Boolean {
    when (phase) {
        PhaseUi.Ready -> return true

        PhaseUi.Loading -> item(key = "loading") { HbLoadingState(stringResource(Res.string.harness_loading)) }

        PhaseUi.NotFound -> item(key = "not-found") {
            HbEmptyState(stringResource(Res.string.harness_not_found), Modifier.testTag("harness-not-found"))
        }

        PhaseUi.Failed -> item(key = "failed") {
            HbEmptyState(
                stringResource(Res.string.harness_load_failed),
                Modifier.testTag("harness-load-failed"),
                action = onRetry?.let { retry ->
                    {
                        HbButton(
                            stringResource(Res.string.harness_retry),
                            retry,
                            Modifier.testTag("harness-retry"),
                            style = HbButtonStyle.Secondary,
                            size = HbButtonSize.Small,
                        )
                    }
                },
            )
        }
    }
    return false
}
