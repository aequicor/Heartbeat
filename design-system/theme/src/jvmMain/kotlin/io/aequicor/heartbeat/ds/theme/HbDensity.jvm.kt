package io.aequicor.heartbeat.ds.theme

import io.aequicor.heartbeat.ds.tokens.HbDimensions

internal actual fun defaultHbDimensions(): HbDimensions =
    if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
        HbDimensions.DesktopMacOs
    } else {
        HbDimensions.Desktop
    }
