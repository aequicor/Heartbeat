package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserSurface

/** Embeds and disposes the platform browser with the lifetime of its visible surface. */
@Composable
internal expect fun BrowserWebView(surface: BrowserSurface, modifier: Modifier = Modifier)
