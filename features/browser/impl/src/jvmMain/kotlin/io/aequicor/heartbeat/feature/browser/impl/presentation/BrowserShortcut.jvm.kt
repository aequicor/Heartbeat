package io.aequicor.heartbeat.feature.browser.impl.presentation

internal actual fun isBrowserMetaShortcut(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
