package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_back
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_dismiss
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_write_busy
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_write_changed
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_write_failed
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_write_full
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_write_missing
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_write_unavailable
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_write_unconfirmed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings-width scrolling pane of the library screens. The header with a back button is shown when [onBack] is set
 * (pushed screens and the standalone library); a library embedded in the settings window shows only its content.
 */
@Composable
internal fun ServiceUiPane(
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
                        stringResource(Res.string.service_ui_back),
                        onBack,
                        Modifier.testTag("$tag-back"),
                    )
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                // The soft keyboard must not cover the focused editor at the end of the list.
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize().imePadding()
                    .testTag("$tag-list"),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
                content = content,
            )
        }
    }
}

/** Why a change was not saved, with a button that hides the message. */
@Composable
internal fun WriteNoticeBanner(notice: WriteNoticeUi, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    HbBanner(stringResource(notice.text()), modifier.testTag("service-ui-notice"), tone = notice.tone()) {
        HbButton(
            stringResource(Res.string.service_ui_dismiss),
            onDismiss,
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
    }
}

/** Failures are errors; refusals of a change that can be retried or reviewed are warnings. */
private fun WriteNoticeUi.tone(): HbTone = when (this) {
    WriteNoticeUi.Failed, WriteNoticeUi.Unavailable -> HbTone.Danger

    WriteNoticeUi.Changed, WriteNoticeUi.Missing, WriteNoticeUi.Full, WriteNoticeUi.Busy, WriteNoticeUi.Unconfirmed ->
        HbTone.Warning
}

private fun WriteNoticeUi.text(): StringResource = when (this) {
    WriteNoticeUi.Changed -> Res.string.service_ui_write_changed
    WriteNoticeUi.Missing -> Res.string.service_ui_write_missing
    WriteNoticeUi.Full -> Res.string.service_ui_write_full
    WriteNoticeUi.Busy -> Res.string.service_ui_write_busy
    WriteNoticeUi.Failed -> Res.string.service_ui_write_failed
    WriteNoticeUi.Unavailable -> Res.string.service_ui_write_unavailable
    WriteNoticeUi.Unconfirmed -> Res.string.service_ui_write_unconfirmed
}
