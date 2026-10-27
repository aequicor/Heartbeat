package io.aequicor.heartbeat.core.logging

/**
 * The directory name of this desktop build's per-user data, logs and locks: `Heartbeat`, or the name a dev build
 * states with `-Dheartbeat.data.name` so it runs beside an installed Heartbeat without sharing its profiles, secrets,
 * logs or single-instance lock. The property is fixed by the launcher before the application starts.
 */
public fun desktopDataName(property: String? = System.getProperty(DATA_NAME_PROPERTY)): String =
    property?.takeIf { it.isNotBlank() && it.matches(DATA_NAME) } ?: DEFAULT_DATA_NAME

/** [desktopDataName] as the lowercase name of the XDG directories on Linux. */
public fun desktopDataNameLowercase(name: String = desktopDataName()): String = name.lowercase()

private const val DATA_NAME_PROPERTY = "heartbeat.data.name"
private const val DEFAULT_DATA_NAME = "Heartbeat"
private val DATA_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
