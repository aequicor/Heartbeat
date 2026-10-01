package io.aequicor.heartbeat.feature.attachments.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.aequicor.heartbeat.feature.attachments.impl.presentation.component.AttachmentComponent

@Composable
internal actual fun AttachmentNativeHost(component: AttachmentComponent) {
    val host = LocalContext.current
    LaunchedEffect(component, host) { component.runNativeHost(host) }
}
