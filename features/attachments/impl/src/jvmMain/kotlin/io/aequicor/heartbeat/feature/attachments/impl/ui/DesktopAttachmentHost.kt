package io.aequicor.heartbeat.feature.attachments.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.aequicor.heartbeat.feature.attachments.impl.presentation.component.AttachmentComponent

@Composable
internal actual fun AttachmentNativeHost(component: AttachmentComponent) {
    LaunchedEffect(component) { component.runNativeHost(null) }
}
