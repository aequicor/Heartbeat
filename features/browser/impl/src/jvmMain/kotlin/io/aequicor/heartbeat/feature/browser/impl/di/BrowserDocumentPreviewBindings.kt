package io.aequicor.heartbeat.feature.browser.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbSpacing
import io.aequicor.heartbeat.ds.tokens.HbTypography
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPreviewThemes
import io.aequicor.heartbeat.feature.browser.impl.ui.browserDocumentTheme
import org.cef.OS

/** Reuses the visible document's design-system mapping without a presentation-to-UI dependency. */
@BindingContainer
@ContributesTo(ProfileScope::class)
public object BrowserDocumentPreviewBindings {
    @Provides
    internal fun themes(): DesktopDocumentPreviewThemes = DesktopDocumentPreviewThemes { isDark, fontScale ->
        val colors = if (isDark) HbColors.DesktopDark else HbColors.DesktopLight
        val dimensions = if (OS.isMacintosh()) HbDimensions.DesktopMacOs else HbDimensions.Desktop
        colors.browserDocumentTheme(HbTypography.Desktop, HbSpacing(), dimensions, fontScale)
    }
}
