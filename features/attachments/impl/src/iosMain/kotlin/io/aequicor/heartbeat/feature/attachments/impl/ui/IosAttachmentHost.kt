package io.aequicor.heartbeat.feature.attachments.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.interop.LocalUIViewController
import io.aequicor.heartbeat.feature.attachments.impl.presentation.component.AttachmentComponent

@Composable
internal actual fun AttachmentNativeHost(component: AttachmentComponent) {
    val host = LocalUIViewController.current
    LaunchedEffect(component, host) { component.runNativeHost(host) }
}
