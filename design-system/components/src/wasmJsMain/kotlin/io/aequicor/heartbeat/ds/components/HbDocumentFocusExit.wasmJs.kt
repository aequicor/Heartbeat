package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberHbDocumentWindowFocus(): () -> Unit = remember { {} }
