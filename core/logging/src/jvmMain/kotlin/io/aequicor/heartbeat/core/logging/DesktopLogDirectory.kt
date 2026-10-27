package io.aequicor.heartbeat.core.logging

import java.nio.file.Path

/** Shared per-user destination for the desktop writer and diagnostic exports. */
public fun desktopLogDirectory(
    os: String = System.getProperty("os.name"),
    home: String = System.getProperty("user.home"),
    localAppData: String? = System.getenv("LOCALAPPDATA"),
    stateHome: String? = System.getenv("XDG_STATE_HOME"),
): Path = when {
    os.startsWith("Mac", ignoreCase = true) -> Path.of(home, "Library", "Logs", desktopDataName())

    os.startsWith("Windows", ignoreCase = true) -> legacyLogDirectory(home, localAppData)

    else -> stateHome?.takeIf { it.isNotBlank() && Path.of(it).isAbsolute }
        ?.let { Path.of(it, desktopDataNameLowercase(), "logs") }
        ?: Path.of(home, ".local", "state", desktopDataNameLowercase(), "logs")
}

/** Previous Linux releases used the Windows fallback; retained records there remain exportable. */
public fun desktopLegacyLogDirectories(
    os: String = System.getProperty("os.name"),
    home: String = System.getProperty("user.home"),
    localAppData: String? = System.getenv("LOCALAPPDATA"),
): List<Path> = if (os.startsWith("Mac", true) || os.startsWith("Windows", true)) {
    emptyList()
} else {
    listOf(legacyLogDirectory(home, localAppData))
}

private fun legacyLogDirectory(home: String, localAppData: String?): Path =
    localAppData?.takeIf(String::isNotBlank)?.let { Path.of(it, "Aequicor", desktopDataName(), "logs") }
        ?: Path.of(home, "AppData", "Local", "Aequicor", desktopDataName(), "logs")
