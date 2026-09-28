package io.aequicor.heartbeat.ds.theme

import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbStudioDimensions

internal actual fun defaultHbDimensions(): HbDimensions = HbDimensions().let { it.copy(touchTarget = it.controlHeight) }

internal actual fun defaultHbStudioDimensions(): HbStudioDimensions =
    if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
        HbStudioDimensions.DesktopMacOs
    } else {
        HbStudioDimensions.Desktop
    }
