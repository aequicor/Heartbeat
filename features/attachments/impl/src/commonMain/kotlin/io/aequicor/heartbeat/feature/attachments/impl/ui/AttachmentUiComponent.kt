package io.aequicor.heartbeat.feature.attachments.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbImage
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.attachments.impl.presentation.component.AttachmentComponent
import io.aequicor.heartbeat.feature.attachments.impl.presentation.component.AttachmentScreenIntent
import io.aequicor.heartbeat.feature.attachments.impl.presentation.component.AttachmentScreenState
import io.aequicor.heartbeat.feature.attachments.impl.resources.Res
import io.aequicor.heartbeat.feature.attachments.impl.resources.attachment_close
import io.aequicor.heartbeat.feature.attachments.impl.resources.attachment_error
import io.aequicor.heartbeat.feature.attachments.impl.resources.attachment_loading
import io.aequicor.heartbeat.feature.attachments.impl.resources.attachment_open
import io.aequicor.heartbeat.feature.attachments.impl.resources.attachment_picking
import io.aequicor.heartbeat.feature.attachments.impl.resources.attachment_save
import io.aequicor.heartbeat.feature.attachments.impl.resources.attachment_text_truncated
import io.aequicor.heartbeat.feature.attachments.impl.resources.attachment_title
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Rendering adapter; business commands remain in the presentation component. */
internal class AttachmentUiComponent(private val component: AttachmentComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val state by produceState(AttachmentScreenState(), component) {
            component.store.collect { states.collect { value = it } }
        }
        // Navigation's full-screen modifier belongs to the underlying host, not to the modal window.
        AttachmentScreen(state, component.store::intent, component::close) { AttachmentNativeHost(component) }
    }
}

@Composable
internal expect fun AttachmentNativeHost(component: AttachmentComponent)

@Composable
internal fun AttachmentScreen(
    state: AttachmentScreenState,
    onIntent: (AttachmentScreenIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    nativeHost: @Composable () -> Unit = {},
) {
    HbDialog(
        title = state.name.ifBlank { stringResource(Res.string.attachment_title) },
        onDismissRequest = onClose,
        modifier = modifier,
        actions = {
            if (!state.isLoading) {
                HbButton(
                    stringResource(Res.string.attachment_save),
                    { onIntent(AttachmentScreenIntent.Export) },
                    style = HbButtonStyle.Secondary,
                    enabled = !state.isBusy && !state.hasError,
                )
            }
            HbButton(stringResource(Res.string.attachment_close), onClose, style = HbButtonStyle.Ghost)
        },
    ) {
        nativeHost()
        if (state.hasError) HbBanner(stringResource(Res.string.attachment_error))
        if (state.isLoading) {
            HbLoadingState(
                stringResource(if (state.isPicking) Res.string.attachment_picking else Res.string.attachment_loading),
            )
        } else {
            AttachmentPreviewBody(state, onIntent)
        }
    }
}

@Composable
private fun AttachmentPreviewBody(state: AttachmentScreenState, onIntent: (AttachmentScreenIntent) -> Unit) {
    if (state.isTextTruncated) {
        HbText(
            stringResource(Res.string.attachment_text_truncated),
            color = HbTheme.colors.textSecondary,
            style = HbTheme.typography.caption,
        )
    }
    if (state.isImage) state.bytes?.let { HbImage(it, state.name, Modifier.fillMaxWidth()) }
    state.text?.let { HbText(it, style = HbTheme.typography.body) }
    if (!state.isImage && state.text == null && !state.hasError) {
        HbButton(
            stringResource(Res.string.attachment_open),
            { onIntent(AttachmentScreenIntent.Open) },
            style = HbButtonStyle.Ghost,
            enabled = !state.isBusy,
        )
    }
}
