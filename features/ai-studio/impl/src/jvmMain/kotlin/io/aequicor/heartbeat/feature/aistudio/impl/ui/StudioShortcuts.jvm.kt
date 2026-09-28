package io.aequicor.heartbeat.feature.aistudio.impl.ui

internal actual fun isStudioMetaShortcut(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
